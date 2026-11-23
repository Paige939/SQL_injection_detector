package tw.edu.cse.nsysu.api.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DefenseService {
    public record Prediction(double score, double threshold, String action,
            List<Map<String, Object>> matchedRules) {}
    public interface Predictor {
        Prediction predict(String input, String architecture, IncrementalAlgorithm algorithm) throws Exception;
        String version();
    }
    @FunctionalInterface public interface Products { List<DefenseStore.Product> search(String input) throws Exception; }
    @FunctionalInterface public interface Audit { void save(Result result) throws Exception; }
    public static final class Request {
        public String keyword;
        public String architecture;
        public String algorithm;
    }
    public static final class ReviewRequest {
        public String requestId;
        public String decision;
    }
    public static final class Result {
        public String requestId, timestamp, architecture, algorithm, modelVersion;
        public String mode = "ENFORCE";
        public String recommendedAction, effectiveAction, reason, errorCode;
        public String status = "started";
        public int inputLength;
        public boolean businessQueryAttempted, businessQueryCompleted;
        public Double score, threshold;
        public double detectionDurationMs, queryDurationMs, totalDurationMs;
        public List<Map<String, Object>> matchedRules = List.of();
        public List<DefenseStore.Product> products = List.of();
    }
    private record PendingReview(String keyword, Result result, long expiresAtNanos) {}
    private static final long REVIEW_TTL_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
    private final Predictor predictor;
    private final Products products;
    private final Audit audit;
    private final Map<String, PendingReview> pendingReviews = new ConcurrentHashMap<>();

    public DefenseService(Predictor predictor, Products products, Audit audit) {
        this.predictor = predictor;
        this.products = products;
        this.audit = audit;
    }

    public static IncrementalAlgorithm validateSelection(String architecture, String algorithm) {
        if (architecture == null || !FrozenArchitectureComparisonService.ARCHITECTURES.contains(architecture)) {
            throw new IllegalArgumentException("Choose one of the five supported architectures.");
        }
        IncrementalAlgorithm selected = IncrementalAlgorithm.fromId(algorithm);
        if (!"EXTENDED_ARM".equals(architecture)
                && selected != IncrementalAlgorithm.CURRENT_ONLINE_BAGGING_NB) {
            throw new IllegalArgumentException("Algorithm selection is supported only for Extended ARM.");
        }
        return selected;
    }

    public Result search(Request request) throws Exception {
        if (request == null || request.keyword == null || request.keyword.isBlank()
                || request.keyword.length() > 100) {
            throw new IllegalArgumentException("Search keyword must contain 1..100 characters.");
        }
        pruneExpiredReviews();
        var algorithm = validateSelection(request.architecture, request.algorithm);
        Result result = new Result();
        result.requestId = UUID.randomUUID().toString();
        result.timestamp = Instant.now().toString();
        result.modelVersion = predictor.version();
        result.architecture = request.architecture;
        result.algorithm = "EXTENDED_ARM".equals(request.architecture) ? algorithm.getId() : "ARCHITECTURE_DEFAULT";
        result.inputLength = request.keyword.length();
        long started = System.nanoTime();
        // A durable start precedes any prediction or business operation.
        saveAudit(result);
        Prediction prediction;
        long detectionStarted = System.nanoTime();
        try {
            prediction = predictor.predict(request.keyword, request.architecture, algorithm);
            if (!Double.isFinite(prediction.score()) || prediction.score() < 0 || prediction.score() > 1
                    || !Double.isFinite(prediction.threshold()) || prediction.threshold() < 0
                    || prediction.threshold() > 1
                    || !List.of("allow", "block", "review").contains(prediction.action())) {
                throw new IllegalStateException("Invalid defense prediction.");
            }
        } catch (Exception error) {
            result.detectionDurationMs = elapsed(detectionStarted);
            result.errorCode = "DETECTOR_UNAVAILABLE";
            result.effectiveAction = "deny";
            finish(result, started, "failed");
            throw new Failure(503, result, error);
        }
        result.detectionDurationMs = elapsed(detectionStarted);
        result.score = prediction.score();
        result.threshold = prediction.threshold();
        result.matchedRules = prediction.matchedRules();
        result.recommendedAction = prediction.action();
        result.effectiveAction = prediction.action();
        if ("review".equals(prediction.action())) {
            result.reason = "MANUAL_REVIEW_REQUIRED";
            finish(result, started, "review_pending");
            rememberReview(result, request.keyword);
            return result;
        }
        if ("block".equals(prediction.action())) {
            result.reason = "DETECTION_THRESHOLD_EXCEEDED";
            finish(result, started, "rejected");
            return result;
        }
        // Persist the attempted state before entering the repository. A crash leaves an
        // explicit incomplete attempt, never a false claim that a query did not run.
        result.businessQueryAttempted = true;
        result.status = "query_pending";
        try {
            saveAudit(result);
        } catch (Failure error) {
            result.businessQueryAttempted = false;
            result.effectiveAction = "deny";
            throw error;
        }
        long queryStarted = System.nanoTime();
        try {
            result.products = products.search(request.keyword);
            result.businessQueryCompleted = true;
        } catch (Exception error) {
            result.queryDurationMs = elapsed(queryStarted);
            result.errorCode = "PRODUCT_QUERY_FAILED";
            finish(result, started, "failed");
            throw new Failure(500, result, error);
        }
        result.queryDurationMs = elapsed(queryStarted);
        finish(result, started, "complete");
        return result;
    }

    public Result resolveReview(ReviewRequest request) throws Exception {
        if (request == null || request.requestId == null || request.requestId.isBlank()) {
            throw new IllegalArgumentException("A pending review request ID is required.");
        }
        if (!"allow".equals(request.decision) && !"block".equals(request.decision)) {
            throw new IllegalArgumentException("Review decision must be allow or block.");
        }
        PendingReview pending = pendingReviews.remove(request.requestId);
        if (pending == null || System.nanoTime() - pending.expiresAtNanos() >= 0) {
            throw new ReviewResolutionFailure(409, "REVIEW_NOT_PENDING");
        }

        Result result = pending.result();
        long resolutionStarted = System.nanoTime();
        if ("block".equals(request.decision)) {
            result.effectiveAction = "block";
            result.reason = "USER_BLOCKED_REVIEW";
            finishResolution(result, resolutionStarted, "rejected");
            return result;
        }

        result.effectiveAction = "allow";
        result.reason = "USER_ALLOWED_REVIEW";
        result.businessQueryAttempted = true;
        result.status = "query_pending";
        try {
            saveAudit(result);
        } catch (Failure error) {
            result.businessQueryAttempted = false;
            result.effectiveAction = "deny";
            throw error;
        }

        long queryStarted = System.nanoTime();
        try {
            result.products = products.search(pending.keyword());
            result.businessQueryCompleted = true;
        } catch (Exception error) {
            result.queryDurationMs = elapsed(queryStarted);
            result.errorCode = "PRODUCT_QUERY_FAILED";
            finishResolution(result, resolutionStarted, "failed");
            throw new Failure(500, result, error);
        }
        result.queryDurationMs = elapsed(queryStarted);
        finishResolution(result, resolutionStarted, "complete");
        return result;
    }

    private void rememberReview(Result result, String keyword) {
        pruneExpiredReviews();
        long now = System.nanoTime();
        pendingReviews.put(result.requestId,
                new PendingReview(keyword, result, now + REVIEW_TTL_NANOS));
    }

    private void pruneExpiredReviews() {
        long now = System.nanoTime();
        pendingReviews.entrySet().removeIf(entry -> now - entry.getValue().expiresAtNanos() >= 0);
    }

    private void finish(Result result, long started, String status) throws Exception {
        result.status = status;
        result.totalDurationMs = elapsed(started);
        saveAudit(result);
    }

    private void finishResolution(Result result, long started, String status) throws Exception {
        result.status = status;
        result.totalDurationMs += elapsed(started);
        saveAudit(result);
    }

    private void saveAudit(Result result) throws Failure {
        try {
            audit.save(result);
        } catch (Exception error) {
            result.status = "audit_failed";
            result.errorCode = "AUDIT_UNAVAILABLE";
            if (result.effectiveAction == null) result.effectiveAction = "deny";
            throw new Failure(503, result, error);
        }
    }

    static double elapsed(long started) { return (System.nanoTime() - started) / 1_000_000.0; }
    public static final class Failure extends Exception {
        public final int httpStatus;
        public final Result result;
        Failure(int status, Result result, Exception cause) {
            super(result.errorCode, cause);
            httpStatus = status;
            this.result = result;
        }
    }
    public static final class ReviewResolutionFailure extends Exception {
        public final int httpStatus;
        public final String errorCode;
        public ReviewResolutionFailure(int status, String code) {
            super(code);
            httpStatus = status;
            errorCode = code;
        }
    }
}

package tw.edu.cse.nsysu.api.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.commons.csv.CSVFormat;

public final class DefenseEvaluation {
    public static final class Request {
        public boolean extendedOnly;
        public int sampleSize = 1000;
    }
    private record Row(String input, int label) {}
    private final DefenseService.Predictor predictor;
    private final Connection training;
    private final Path dataset;
    private final DefenseStore store;

    public DefenseEvaluation(DefenseService.Predictor predictor, Connection training,
            Path dataset, DefenseStore store) {
        this.predictor = predictor;
        this.training = training;
        this.dataset = dataset;
        this.store = store;
    }

    public synchronized Map<String, Object> run(Request request) throws Exception {
        if (request == null || request.sampleSize < 1 || request.sampleSize > 10000) {
            throw new IllegalArgumentException("Evaluation sample size must be 1..10000.");
        }
        List<Row> rows = new ArrayList<>();
        try (var reader = Files.newBufferedReader(dataset, StandardCharsets.UTF_8);
             var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            for (var record : parser) {
                EvaluationDatasetSupport.requireTestSplit(record);
                String input = EvaluationDatasetSupport.requireSql(record);
                SingleInputValidation.requireValid(input);
                rows.add(new Row(input, EvaluationDatasetSupport.requireBinaryLabel(record)));
            }
        }
        if (rows.isEmpty() || rows.stream().map(Row::label).distinct().count() != 2) {
            throw new IllegalArgumentException("Test must contain both labeled classes.");
        }
        EvaluationDatasetSupport.rejectTrainingOverlap(training, rows.stream().map(Row::input).toList());
        Collections.shuffle(rows, new Random(42));
        rows = List.copyOf(rows.subList(0, Math.min(request.sampleSize, rows.size())));
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> architectures = request.extendedOnly
                ? List.of("PURE_EXTENDED_ARM", "EXTENDED_ARM")
                : FrozenArchitectureComparisonService.ARCHITECTURES;
        for (String architecture : architectures) {
            var algorithms = "EXTENDED_ARM".equals(architecture)
                    ? IncrementalAlgorithm.available() : List.of(IncrementalAlgorithm.CURRENT_ONLINE_BAGGING_NB);
            for (var algorithm : algorithms) results.add(score(rows, architecture, algorithm));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "complete");
        report.put("timestamp", Instant.now().toString());
        report.put("modelVersion", predictor.version());
        report.put("evaluationMode", "session_fixed_defense_test");
        report.put("dataset", "Test dataset");
        report.put("extendedOnly", request.extendedOnly);
        report.put("sampleSize", rows.size());
        report.put("updatesEnabled", false);
        report.put("businessQueriesExecuted", 0);
        report.put("note", "Decision-only Test evaluation, not a product-search or independent blind-test result. "
                + "Batch review decisions are counted as not automatically allowed; interactive searches wait for an explicit user choice. "
                + "Policy rejection differs from binary FPR. "
                + "Latency includes prediction only, not HTTP, audit or database work.");
        report.put("results", results);
        store.saveReport(report);
        return report;
    }

    private Map<String, Object> score(List<Row> rows, String architecture, IncrementalAlgorithm algorithm)
            throws Exception {
        int tp = 0, fp = 0, tn = 0, fn = 0, benignDenied = 0, attackAllowed = 0, review = 0;
        List<Double> latencies = new ArrayList<>();
        double threshold = 0;
        for (Row row : rows) {
            long started = System.nanoTime();
            var prediction = predictor.predict(row.input(), architecture, algorithm);
            latencies.add(DefenseService.elapsed(started));
            threshold = prediction.threshold();
            boolean malicious = prediction.score() >= threshold;
            if (malicious && row.label() == 1) tp++;
            else if (malicious) fp++;
            else if (row.label() == 0) tn++;
            else fn++;
            boolean allowed = "allow".equals(prediction.action());
            if (row.label() == 0 && !allowed) benignDenied++;
            if (row.label() == 1 && allowed) attackAllowed++;
            if ("review".equals(prediction.action())) review++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("architecture", architecture);
        result.put("algorithm", "EXTENDED_ARM".equals(architecture) ? algorithm.getId() : "ARCHITECTURE_DEFAULT");
        result.put("total", rows.size());
        result.put("tp", tp); result.put("fp", fp); result.put("tn", tn); result.put("fn", fn);
        result.put("accuracy", ratio(tp + tn, rows.size()));
        result.put("precision", ratio(tp, tp + fp));
        result.put("recall", ratio(tp, tp + fn));
        result.put("f1", ratio(2 * tp, 2 * tp + fp + fn));
        result.put("fpr", ratio(fp, fp + tn));
        result.put("benignDenialRate", ratio(benignDenied, fp + tn));
        result.put("attackPassRate", ratio(attackAllowed, tp + fn));
        result.put("reviewCount", review);
        result.put("threshold", threshold);
        result.put("detectionP50Ms", percentile(latencies, 0.5));
        result.put("detectionP95Ms", percentile(latencies, 0.95));
        return result;
    }

    // Undefined metrics remain null rather than looking like a measured zero.
    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    static Double percentile(List<Double> values, double fraction) {
        if (values.isEmpty()) return null;
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * fraction) - 1));
    }

    public static Map<String, Object> auditSummary(List<DefenseService.Result> rows) {
        Map<String, List<DefenseService.Result>> groups = new LinkedHashMap<>();
        for (var row : rows) {
            String key = row.modelVersion + "/" + row.architecture + "/" + row.algorithm;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (var group : groups.values()) {
            var first = group.getFirst();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("modelVersion", first.modelVersion);
            result.put("architecture", first.architecture);
            result.put("algorithm", first.algorithm);
            result.put("requests", group.size());
            result.put("rejected", group.stream().filter(row -> "rejected".equals(row.status)).count());
            result.put("incompleteOrFailed", group.stream().filter(row ->
                    !List.of("complete", "rejected").contains(row.status)).count());
            result.put("rejectedQueryAttempts", group.stream().filter(row ->
                    "rejected".equals(row.status) && row.businessQueryAttempted).count());
            var completed = group.stream().filter(row -> List.of("complete", "rejected").contains(row.status)).toList();
            result.put("totalP50Ms", percentile(completed.stream().map(row -> row.totalDurationMs).toList(), 0.5));
            result.put("totalP95Ms", percentile(completed.stream().map(row -> row.totalDurationMs).toList(), 0.95));
            results.add(result);
        }
        return Map.of("sampleSize", rows.size(), "groups", results,
                "note", "Latest audit window only; input labels are unknown. Zero recorded attempts "
                + "is an observation, not a substitute for automated gate tests. Total timing ends before the final audit write.");
    }
}

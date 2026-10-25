package tw.edu.cse.nsysu.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads immutable external-generalization reports from their experiment folders. */
public final class ExternalReportService {
    private static final String SCHEMA = "sqlia-external-report-v1";
    private static final String REPORT_TYPE = "external_generalization";
    private static final String REPORT_FILE = "external-report.json";
    private static final List<String> METRICS =
            List.of("accuracy", "precision", "recall", "f1", "fpr", "averagePrecision");
    private static final List<String> CONFUSION_FIELDS = List.of("total", "tp", "fp", "tn", "fn");

    private final Path experimentsRoot;
    private final ObjectMapper mapper;
    private final String prefix;
    private final String reportFile;
    private final java.util.function.BiConsumer<JsonNode, String> validator;

    public ExternalReportService(Path experimentsRoot) {
        this(experimentsRoot, new ObjectMapper());
    }

    ExternalReportService(Path experimentsRoot, ObjectMapper mapper) {
        this(experimentsRoot, mapper, "generalization-", REPORT_FILE, ExternalReportService::validateReport);
    }

    ExternalReportService(Path experimentsRoot, ObjectMapper mapper, String prefix, String reportFile,
            java.util.function.BiConsumer<JsonNode, String> validator) {
        this.experimentsRoot = experimentsRoot.toAbsolutePath().normalize();
        this.mapper = mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.prefix = prefix;
        this.reportFile = reportFile;
        this.validator = validator;
    }

    /** Returns full immutable report objects, ordered by run ID. */
    public List<JsonNode> listReports() throws IOException {
        if (!Files.exists(experimentsRoot)) return List.of();
        Path root = experimentsRoot.toRealPath();
        List<Path> directories = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(prefix) && Files.isDirectory(entry)) {
                    directories.add(entry);
                }
            }
        }
        directories.sort((left, right) ->
                left.getFileName().toString().compareTo(right.getFileName().toString()));

        List<JsonNode> reports = new ArrayList<>();
        for (Path directory : directories) {
            String id = directory.getFileName().toString();
            validateId(id);
            reports.add(readReport(root, directory, id));
        }
        return List.copyOf(reports);
    }

    public JsonNode getReport(String id) throws IOException {
        validateId(id);
        Path root = existingRoot();
        Path directory = root.resolve(id);
        if (!Files.exists(directory)) throw new ReportNotFoundException("External report not found.");
        return readReport(root, directory, id);
    }

    public byte[] downloadReport(String id) throws IOException {
        validateId(id);
        Path root = existingRoot();
        Path directory = root.resolve(id);
        if (!Files.exists(directory)) throw new ReportNotFoundException("External report not found.");
        readReport(root, directory, id);
        Path reportPath = safeReportPath(root, directory);
        return Files.readAllBytes(reportPath);
    }

    private Path existingRoot() throws IOException {
        if (!Files.exists(experimentsRoot)) throw new ReportNotFoundException("External report not found.");
        return experimentsRoot.toRealPath();
    }

    private JsonNode readReport(Path root, Path directory, String id) throws IOException {
        Path reportPath = safeReportPath(root, directory);
        JsonNode report;
        try {
            report = mapper.readTree(reportPath.toFile());
        } catch (IOException failure) {
            throw new InvalidReportException("Could not read external report " + id + ".", failure);
        }
        validator.accept(report, id);
        if (report.has("runId") && !report.path("runId").asText().equals(id)) {
            throw new InvalidReportException("Report runId does not match its folder: " + id);
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) report).put("runId", id);
        return report;
    }

    private Path safeReportPath(Path root, Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new InvalidReportException("External report folder must not be a symbolic link.");
        }
        Path realDirectory = directory.toRealPath();
        if (!realDirectory.getParent().equals(root)) {
            throw new InvalidReportException("External report folder is outside experiments.");
        }
        Path reportPath = realDirectory.resolve(reportFile);
        if (!Files.exists(reportPath) || !Files.isRegularFile(reportPath)) {
            throw new ReportNotFoundException("External report file not found.");
        }
        if (Files.isSymbolicLink(reportPath)) {
            throw new InvalidReportException("External report file must not be a symbolic link.");
        }
        Path realReport = reportPath.toRealPath();
        if (!realReport.getParent().equals(realDirectory) || !realReport.startsWith(root)) {
            throw new InvalidReportException("External report file is outside its report folder.");
        }
        return realReport;
    }

    private void validateId(String id) {
        if (id == null || !id.matches(java.util.regex.Pattern.quote(prefix) + "[A-Za-z0-9][A-Za-z0-9._-]*")
                || id.contains("..")) {
            throw new IllegalArgumentException("Invalid external report ID.");
        }
    }

    private static void validateReport(JsonNode report, String id) {
        if (report == null || !report.isObject()) invalid(id, "report must be a JSON object");
        text(report, "schemaVersion", id, SCHEMA);
        text(report, "status", id, null);
        String status = report.path("status").asText();
        if (!Set.of("running", "failed", "complete", "blocked").contains(status)) {
            invalid(id, "unsupported status");
        }
        text(report, "reportType", id, REPORT_TYPE);
        text(report, "runId", id, null);
        text(report, "createdAt", id, null);
        text(report, "evaluationMode", id, "frozen_external_checkpoint");
        required(report, "blindTestEligible", JsonNode::isBoolean, id);
        required(report, "limitations", node -> node.isArray() && allText(node), id);
        if (status.equals("complete")) {
            required(report, "provenance", JsonNode::isObject, id);
        } else if (report.has("provenance") && !report.get("provenance").isNull()
                && !report.get("provenance").isObject()) {
            invalid(id, "provenance must be an object when present");
        }

        JsonNode policy = required(report, "policy", JsonNode::isObject, id);
        for (String field : List.of("retrained", "updated", "thresholdsRetuned")) {
            JsonNode value = required(policy, field, JsonNode::isBoolean, id);
            if (value.asBoolean()) invalid(id, "policy." + field + " must be false");
        }

        if (status.equals("complete")) {
            text(report, "completedAt", id, null);
            validateDataset(required(report, "dataset", JsonNode::isObject, id), id);
            validateModels(required(report, "models", JsonNode::isArray, id), id);
        } else {
            if (report.has("completedAt") && !report.get("completedAt").isNull()) {
                text(report, "completedAt", id, null);
            }
            if (report.has("dataset") && !report.get("dataset").isNull()
                    && !report.get("dataset").isObject()) {
                invalid(id, "dataset must be an object when present");
            }
            if (report.has("models") && !report.get("models").isNull()) {
                if (!report.get("models").isArray()) invalid(id, "models must be an array");
                for (JsonNode model : report.get("models")) {
                    if (!model.isObject()) invalid(id, "models entries must be objects");
                }
            }
        }
    }

    private static void validateDataset(JsonNode dataset, String id) {
        for (String field : List.of("source", "fileSha256", "auditReportSha256")) {
            text(dataset, field, id, null);
        }
        for (String field : List.of(
                "originalRows", "evaluatedRows", "benignCount", "maliciousCount", "excludedRows")) {
            nonNegativeInteger(dataset, field, id);
        }
        required(dataset, "exclusionCounts", JsonNode::isObject, id);
    }

    private static void validateModels(JsonNode models, String id) {
        if (models.isEmpty()) invalid(id, "complete report must contain at least one model");
        Set<String> architectures = new HashSet<>();
        for (JsonNode model : models) {
            if (!model.isObject()) invalid(id, "models entries must be objects");
            text(model, "architecture", id, null);
            if (!architectures.add(model.path("architecture").asText())) {
                invalid(id, "model architectures must be unique");
            }
            text(model, "checkpointSha256", id, null);
            required(model, "threshold", ExternalReportService::isNumber, id);
            required(model, "trainingPolicy",
                    node -> !node.isNull() && (node.isTextual() || node.isObject()), id);
            validateMetrics(required(model, "metrics", JsonNode::isObject, id), id);
            JsonNode intervals = required(model, "confidenceIntervals", JsonNode::isObject, id);
            for (String metric : METRICS) validateInterval(required(intervals, metric,
                    JsonNode::isObject, id), id);
            RankingReportValidation.validate(model, id);
            if (model.has("bySource") && !model.get("bySource").isNull()) {
                JsonNode bySource = model.get("bySource");
                if (!bySource.isObject()) invalid(id, "bySource must be an object");
                bySource.fields().forEachRemaining(entry -> validateMetrics(entry.getValue(), id));
            }
            if (model.has("baselineComparison") && !model.get("baselineComparison").isNull()
                    && !model.get("baselineComparison").isObject()) {
                invalid(id, "baselineComparison must be an object");
            }
        }
    }

    private static void validateMetrics(JsonNode metrics, String id) {
        for (String field : CONFUSION_FIELDS) nonNegativeInteger(metrics, field, id);
        for (String metric : METRICS) required(metrics, metric, ExternalReportService::isNumber, id);
    }

    private static void validateInterval(JsonNode interval, String id) {
        for (String bound : List.of("lower", "upper")) {
            JsonNode value = required(interval, bound, node -> node.isNull() || isNumber(node), id);
            if (isNumber(value) && (value.doubleValue() < 0 || value.doubleValue() > 1)) {
                invalid(id, "confidence interval bounds must be between 0 and 1");
            }
        }
        nonNegativeInteger(interval, "validReplicates", id);
        nonNegativeInteger(interval, "undefinedReplicates", id);
    }

    private static void text(JsonNode object, String field, String id, String expected) {
        JsonNode value = required(object, field, node -> node.isTextual() && !node.asText().isBlank(), id);
        if (expected != null && !value.asText().equals(expected)) {
            invalid(id, field + " has an unsupported value");
        }
    }

    private static void nonNegativeInteger(JsonNode object, String field, String id) {
        required(object, field, node -> node.isIntegralNumber() && node.longValue() >= 0, id);
    }

    private static boolean isNumber(JsonNode node) {
        return node.isNumber() && Double.isFinite(node.doubleValue());
    }

    private static boolean allText(JsonNode node) {
        for (JsonNode value : node) if (!value.isTextual()) return false;
        return true;
    }

    private static JsonNode required(
            JsonNode object, String field, java.util.function.Predicate<JsonNode> valid, String id) {
        JsonNode value = object.get(field);
        if (value == null || !valid.test(value)) invalid(id, "missing or invalid " + field);
        return value;
    }

    private static void invalid(String id, String detail) {
        throw new InvalidReportException("Invalid external report " + id + ": " + detail);
    }

    public static class InvalidReportException extends RuntimeException {
        InvalidReportException(String message) { super(message); }
        InvalidReportException(String message, Throwable cause) { super(message, cause); }
    }

    public static class ReportNotFoundException extends IOException {
        public ReportNotFoundException(String message) { super(message); }
    }
}

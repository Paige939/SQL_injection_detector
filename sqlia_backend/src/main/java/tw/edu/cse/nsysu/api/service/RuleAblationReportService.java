package tw.edu.cse.nsysu.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Only reads saved development experiments; never loads checkpoints or trains models. */
public final class RuleAblationReportService {
    private static final Set<String> POLICIES = Set.of("BASELINE", "NO_NEGATIVE", "SQL_CONTEXT");
    private static final Set<String> ARCHITECTURES = Set.of("PURE_EXTENDED_ARM", "EXTENDED_ARM");
    private static final List<String> METRICS =
            List.of("accuracy", "precision", "recall", "f1", "fpr", "averagePrecision");
    private final ExternalReportService reports;

    public RuleAblationReportService(Path root) {
        reports = new ExternalReportService(root, new ObjectMapper(), "rule-ablation-",
                "ablation-report.json", RuleAblationReportService::validate);
    }

    public List<JsonNode> listReports() throws IOException { return reports.listReports(); }
    public JsonNode getReport(String id) throws IOException { return reports.getReport(id); }
    public byte[] downloadReport(String id) throws IOException { return reports.downloadReport(id); }

    static void validate(JsonNode report, String id) {
        require(report.isObject(), id, "report must be an object");
        require(report.path("schemaVersion").asText().equals("sqlia-rule-ablation-v1"), id, "unsupported schema");
        String status = report.path("status").asText();
        require(Set.of("running", "failed", "complete").contains(status), id, "unsupported status");
        text(report, "createdAt", id);
        if (!status.equals("complete")) return;
        text(report, "completedAt", id);
        hash(report, "frozenManifestSha256", id);
        JsonNode protocol = report.path("protocol");
        require(protocol.isObject(), id, "missing protocol");
        require(protocol.path("websiteModelPromoted").isBoolean()
                && !protocol.path("websiteModelPromoted").booleanValue(), id, "must not be deployed");
        require(protocol.path("oldFinalTestRead").isBoolean()
                && !protocol.path("oldFinalTestRead").booleanValue(), id, "old final Test must remain withheld");
        for (String field : List.of("thresholdSelection", "externalUsage", "candidateControl", "sqlContext", "bootstrap")) {
            text(protocol, field, id);
        }
        require(protocol.path("limitations").isArray(), id, "missing limitations");
        for (JsonNode limitation : protocol.path("limitations")) require(
                limitation.isTextual() && !limitation.asText().isBlank(), id, "invalid limitation");
        Set<Long> seeds = new HashSet<>();
        require(protocol.path("seeds").isArray(), id, "missing seeds");
        for (JsonNode seed : protocol.path("seeds")) require(seed.isIntegralNumber()
                && seeds.add(seed.longValue()), id, "invalid or duplicate seed");
        require(seeds.size() >= 2, id, "at least two seeds required");
        exactNames(protocol.path("policies"), POLICIES, id);
        exactNames(protocol.path("architectures"), ARCHITECTURES, id);
        JsonNode inputs = report.path("verifiedInputSha256");
        require(inputs.isObject() && !inputs.isEmpty(), id, "missing input provenance");
        inputs.fields().forEachRemaining(e -> require(e.getValue().isTextual()
                && e.getValue().asText().matches("[a-f0-9]{64}"), id, "invalid input hash"));
        JsonNode trials = report.path("trials");
        require(trials.isArray() && trials.size() == seeds.size() * POLICIES.size(), id, "incomplete trial matrix");
        Set<String> pairs = new HashSet<>(), ids = new HashSet<>();
        Long trainingRows = null, externalRows = null;
        for (JsonNode trial : trials) {
            text(trial, "id", id);
            require(ids.add(trial.path("id").asText()), id, "duplicate trial ID");
            String policy = trial.path("extendedRulePolicy").asText();
            require(POLICIES.contains(policy), id, "unsupported policy");
            require(trial.path("seed").isIntegralNumber() && seeds.contains(trial.path("seed").longValue()),
                    id, "undeclared seed");
            require(pairs.add(policy + ":" + trial.path("seed").longValue()), id, "duplicate policy/seed");
            hash(trial, "checkpointSha256", id);
            text(trial, "checkpoint", id);
            long rows = integer(trial, "trainingRows", id);
            require(rows > 0 && (trainingRows == null || trainingRows == rows), id, "inconsistent training rows");
            trainingRows = rows;
            long rules = integer(trial, "extendedRuleCount", id);
            require(trial.path("zeroExtendedRules").isBoolean()
                    && trial.path("zeroExtendedRules").booleanValue() == (rules == 0), id, "invalid zero-rule flag");
            require(trial.path("extendedRules").isArray() && trial.path("extendedRules").size() == rules,
                    id, "rule schema count mismatch");
            for (String field : List.of("architectures", "externalDevelopmentDiagnostics")) {
                JsonNode results = trial.path(field);
                require(results.isObject() && results.size() == ARCHITECTURES.size(), id, "incomplete architecture results");
                for (String architecture : ARCHITECTURES) {
                    JsonNode result = results.path(architecture);
                    validateResult(result, id);
                    if (field.equals("externalDevelopmentDiagnostics")) {
                        require(Double.compare(result.path("threshold").doubleValue(),
                                trial.path("architectures").path(architecture).path("threshold").doubleValue()) == 0,
                                id, "external threshold differs from Validation");
                        long total = result.path("metrics").path("total").longValue();
                        require(externalRows == null || externalRows == total, id, "inconsistent external rows");
                        externalRows = total;
                    }
                }
            }
        }
    }

    private static void validateResult(JsonNode result, String id) {
        RankingReportValidation.validate(result, id);
        JsonNode threshold = result.path("threshold");
        require(threshold.isNumber() && Double.isFinite(threshold.doubleValue()) && threshold.doubleValue() >= 0
                && threshold.doubleValue() <= Math.nextUp(1.0), id, "invalid threshold");
        JsonNode metrics = result.path("metrics");
        long total = integer(metrics, "total", id);
        long tp = integer(metrics, "tp", id), fp = integer(metrics, "fp", id);
        long tn = integer(metrics, "tn", id), fn = integer(metrics, "fn", id);
        require(total > 0 && tp + fp + tn + fn == total, id, "confusion counts do not sum to total");
        for (String metric : METRICS) {
            JsonNode value = metrics.path(metric);
            require(value.isNull() || unit(value), id, "missing or invalid metric " + metric);
            JsonNode interval = result.path("confidenceIntervals").path(metric);
            require(interval.isObject(), id, "missing interval " + metric);
            JsonNode lo = interval.path("lower"), hi = interval.path("upper");
            require((lo.isNull() && hi.isNull()) || (unit(lo) && unit(hi)
                    && lo.doubleValue() <= hi.doubleValue()), id, "invalid interval bounds");
            long valid = integer(interval, "validReplicates", id);
            long undefined = integer(interval, "undefinedReplicates", id);
            require(valid + undefined >= 200 && valid + undefined <= 2000
                    && (valid == 0) == lo.isNull(), id, "invalid bootstrap counts");
        }
    }

    private static boolean unit(JsonNode node) {
        return node.isNumber() && Double.isFinite(node.doubleValue())
                && node.doubleValue() >= 0 && node.doubleValue() <= 1;
    }
    private static long integer(JsonNode node, String field, String id) {
        JsonNode value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0,
                id, "missing or invalid " + field);
        return value.longValue();
    }
    private static void text(JsonNode node, String field, String id) {
        require(node.path(field).isTextual() && !node.path(field).asText().isBlank(), id, "missing " + field);
    }
    private static void hash(JsonNode node, String field, String id) {
        require(node.path(field).isTextual() && node.path(field).asText().matches("[a-f0-9]{64}"),
                id, "missing or invalid " + field);
    }
    private static void exactNames(JsonNode array, Set<String> expected, String id) {
        require(array.isArray() && array.size() == expected.size(), id, "invalid declared names");
        Set<String> actual = new HashSet<>();
        for (JsonNode value : array) require(value.isTextual() && actual.add(value.asText()), id, "duplicate declared name");
        require(actual.equals(expected), id, "unsupported declared names");
    }
    private static void require(boolean condition, String id, String detail) {
        if (!condition) throw new ExternalReportService.InvalidReportException(
                "Invalid rule ablation report " + id + ": " + detail);
    }
}

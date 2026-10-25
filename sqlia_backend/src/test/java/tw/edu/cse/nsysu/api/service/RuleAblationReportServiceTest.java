package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tw.edu.cse.nsysu.api.ApiServer;

public class RuleAblationReportServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ID = "rule-ablation-fixture";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private ObjectNode fixture() {
        ObjectNode report = JSON.createObjectNode();
        report.put("schemaVersion", "sqlia-rule-ablation-v1").put("status", "complete")
                .put("createdAt", "2026-10-25T00:00:00Z").put("completedAt", "2026-10-25T01:00:00Z")
                .put("frozenManifestSha256", "a".repeat(64));
        report.putObject("verifiedInputSha256").put("train.csv", "b".repeat(64));
        ObjectNode protocol = report.putObject("protocol");
        protocol.put("websiteModelPromoted", false).put("oldFinalTestRead", false);
        for (String key : new String[]{"thresholdSelection", "externalUsage", "candidateControl", "sqlContext", "bootstrap"}) {
            protocol.put(key, "Development only");
        }
        protocol.putArray("limitations").add("Not an independent blind test.");
        protocol.putArray("seeds").add(42).add(43);
        protocol.putArray("policies").add("BASELINE").add("NO_NEGATIVE").add("SQL_CONTEXT");
        protocol.putArray("architectures").add("PURE_EXTENDED_ARM").add("EXTENDED_ARM");
        ArrayNode trials = report.putArray("trials");
        for (int seed : new int[]{42, 43}) for (String policy : new String[]{"BASELINE", "NO_NEGATIVE", "SQL_CONTEXT"}) {
            ObjectNode trial = trials.addObject();
            trial.put("id", policy + "-" + seed).put("extendedRulePolicy", policy).put("seed", seed)
                    .put("checkpointSha256", "c".repeat(64)).put("checkpoint", "models/" + policy + seed + "/model.ser")
                    .put("trainingRows", 20).put("extendedRuleCount", 0).put("zeroExtendedRules", true);
            trial.putArray("extendedRules");
            for (String field : new String[]{"architectures", "externalDevelopmentDiagnostics"}) {
                ObjectNode results = trial.putObject(field);
                for (String architecture : new String[]{"PURE_EXTENDED_ARM", "EXTENDED_ARM"}) {
                    ObjectNode result = results.putObject(architecture);
                    result.put("threshold", .9999999999999896);
                    ObjectNode metrics = result.putObject("metrics");
                    metrics.put("total", 10).put("tp", 4).put("fp", 2).put("tn", 3).put("fn", 1);
                    ObjectNode intervals = result.putObject("confidenceIntervals");
                    for (String metric : new String[]{"accuracy", "precision", "recall", "f1", "fpr", "averagePrecision"}) {
                        metrics.put(metric, .5);
                        intervals.putObject(metric).put("lower", .4).put("upper", .6)
                                .put("validReplicates", 200).put("undefinedReplicates", 0);
                    }
                }
            }
        }
        return report;
    }

    private Path save(JsonNode report) throws Exception {
        Path root = temporary.getRoot().toPath();
        Files.createDirectories(root.resolve(ID));
        Files.write(root.resolve(ID).resolve("ablation-report.json"), JSON.writeValueAsBytes(report));
        return root;
    }

    @Test public void listsDetailsAndDownloadsOriginalBytesWithoutMutatingFiles() throws Exception {
        Path root = save(fixture());
        byte[] before = Files.readAllBytes(root.resolve(ID).resolve("ablation-report.json"));
        RuleAblationReportService service = new RuleAblationReportService(root);
        assertEquals(ID, service.listReports().getFirst().path("runId").asText());
        assertEquals(6, service.getReport(ID).path("trials").size());
        assertArrayEquals(before, service.downloadReport(ID));
        assertArrayEquals(before, Files.readAllBytes(root.resolve(ID).resolve("ablation-report.json")));
        assertEquals(0, new ExternalReportService(root).listReports().size());
    }

    @Test public void rejectsMissingMetricsIntervalsThresholdDriftAndDuplicateSeeds() {
        for (String corruption : new String[]{"metric", "interval", "threshold", "seed", "deployed", "counts"}) {
            ObjectNode report = fixture();
            ObjectNode trial = (ObjectNode) report.path("trials").get(0);
            ObjectNode result = (ObjectNode) trial.path("externalDevelopmentDiagnostics").path("EXTENDED_ARM");
            switch (corruption) {
                case "metric" -> ((ObjectNode) result.path("metrics")).remove("accuracy");
                case "interval" -> ((ObjectNode) result.path("confidenceIntervals")).remove("recall");
                case "threshold" -> result.put("threshold", 1);
                case "seed" -> trial.put("seed", 99);
                case "deployed" -> ((ObjectNode) report.path("protocol")).put("websiteModelPromoted", true);
                case "counts" -> ((ObjectNode) result.path("metrics")).put("tp", 99);
            }
            assertThrows(corruption, ExternalReportService.InvalidReportException.class,
                    () -> RuleAblationReportService.validate(report, ID));
        }
        ObjectNode duplicate = fixture();
        ((ObjectNode) duplicate.path("trials").get(1)).put("extendedRulePolicy", "BASELINE");
        assertThrows(ExternalReportService.InvalidReportException.class,
                () -> RuleAblationReportService.validate(duplicate, ID));
    }

    @Test public void preservesUndefinedMetricsAndIncompleteStatuses() throws Exception {
        ObjectNode report = fixture();
        ObjectNode result = (ObjectNode) report.path("trials").get(0)
                .path("externalDevelopmentDiagnostics").path("EXTENDED_ARM");
        ((ObjectNode) result.path("metrics")).putNull("precision");
        ((ObjectNode) result.path("confidenceIntervals").path("precision"))
                .putNull("lower").putNull("upper").put("validReplicates", 0).put("undefinedReplicates", 200);
        RuleAblationReportService.validate(report, ID);
        for (String status : new String[]{"running", "failed"}) {
            report = JSON.createObjectNode().put("schemaVersion", "sqlia-rule-ablation-v1")
                    .put("status", status).put("createdAt", "today");
            Path root = save(report);
            assertEquals(status, new RuleAblationReportService(root).getReport(ID).path("status").asText());
        }
    }

    @Test public void rejectsUnsafeIdsSymlinksAndTrailingJson() throws Exception {
        Path root = save(fixture());
        RuleAblationReportService service = new RuleAblationReportService(root);
        assertThrows(IllegalArgumentException.class, () -> service.getReport("../secret"));
        assertThrows(IllegalArgumentException.class, () -> service.downloadReport("generalization-fixture"));
        Path file = root.resolve(ID).resolve("ablation-report.json");
        Files.writeString(file, JSON.writeValueAsString(fixture()) + " trailing");
        assertThrows(ExternalReportService.InvalidReportException.class, () -> service.getReport(ID));
        Path outside = temporary.newFile("outside.json").toPath();
        Files.write(outside, JSON.writeValueAsBytes(fixture()));
        Files.delete(file);
        Files.createSymbolicLink(file, outside);
        assertThrows(ExternalReportService.InvalidReportException.class, () -> service.downloadReport(ID));
    }

    @Test public void validatesRealCompletedExperimentWhenPresent() throws Exception {
        Path root = Path.of("data/experiments");
        String id = "rule-ablation-20261025-1427";
        Assume.assumeTrue(Files.exists(root.resolve(id).resolve("ablation-report.json")));
        JsonNode report = new RuleAblationReportService(root).getReport(id);
        assertEquals("complete", report.path("status").asText());
        assertEquals(6, report.path("trials").size());
    }

    @Test public void servesOnlyReadOnlyRoutesWithExplicitErrors() throws Exception {
        Path root = save(fixture());
        Javalin app = Javalin.create();
        ApiServer.registerRuleAblationReportRoutes(app, new RuleAblationReportService(root));
        app.start(0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + app.port() + "/api/experiments/rule-ablation/reports";
            for (String suffix : new String[]{"", "/" + ID, "/" + ID + "/download"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create(base + suffix)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                if (suffix.endsWith("download")) assertTrue(response.headers()
                        .firstValue("Content-Disposition").orElseThrow().contains(ID + "-ablation-report.json"));
            }
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(base + "/rule-ablation-missing"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400, client.send(HttpRequest.newBuilder(URI.create(base + "/invalid"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(base))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            save(fixture().put("status", "unsupported"));
            var invalid = client.send(HttpRequest.newBuilder(URI.create(base)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(500, invalid.statusCode());
            assertTrue(invalid.body().contains("error"));
        } finally { app.stop(); }
    }
}

package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Assume;
import org.junit.rules.TemporaryFolder;
import tw.edu.cse.nsysu.api.ApiServer;

public class ExternalReportServiceTest {
    private static final String RUN_ID = "generalization-fixture";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void missingExperimentsDirectoryIsAnEmptyList() throws Exception {
        ExternalReportService service = new ExternalReportService(
                temporary.getRoot().toPath().resolve("missing"));
        assertTrue(service.listReports().isEmpty());
    }

    @Test
    public void readsAllArchitectureResultsInSavedReports() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("experiments");
        writeReport(root, RUN_ID, completeReport(RUN_ID));

        var reports = new ExternalReportService(root).listReports();
        assertEquals(1, reports.size());
        assertEquals(2, reports.get(0).path("models").size());
        assertEquals("PURE_EXTENDED_ARM",
                reports.get(0).path("models").get(0).path("architecture").asText());
        assertEquals("EXTENDED_ARM",
                reports.get(0).path("models").get(1).path("architecture").asText());
    }

    @Test
    public void validatesFinalizedGeneralizationRunWhenPresent() throws Exception {
        Path experiments = Path.of("data/experiments");
        Path reportFile = experiments.resolve("generalization-20261025-1322")
                .resolve("external-report.json");
        Assume.assumeTrue("The one-time external evaluation has not run.",
                Files.isRegularFile(reportFile));

        JsonNode report = new ExternalReportService(experiments)
                .getReport("generalization-20261025-1322");
        assertEquals("complete", report.path("status").asText());
        assertEquals("sqlia-external-report-v1", report.path("schemaVersion").asText());
        assertEquals(5, report.path("models").size());
        assertEquals(101289, report.path("dataset").path("evaluatedRows").asInt());
        assertEquals(48879, report.path("dataset").path("benignCount").asInt());
        assertEquals(52410, report.path("dataset").path("maliciousCount").asInt());
        assertFalse(report.path("blindTestEligible").asBoolean());
    }

    @Test
    public void failedReportMayOmitCompletedEvaluationFields() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("experiments");
        ObjectNode report = baseReport(RUN_ID, "failed");
        report.remove("provenance");
        report.putObject("dataset");
        report.putArray("models");
        writeReport(root, RUN_ID, report);

        JsonNode loaded = new ExternalReportService(root).getReport(RUN_ID);
        assertEquals("failed", loaded.path("status").asText());
        assertTrue(loaded.path("dataset").isEmpty());
        assertTrue(loaded.path("models").isEmpty());
        assertFalse(loaded.has("provenance"));
    }

    @Test
    public void nullIntervalBoundsArePreservedAndRequiredIntervalsAreChecked() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("experiments");
        ObjectNode report = completeReport(RUN_ID);
        ((ObjectNode) report.withArray("models").get(0).path("confidenceIntervals")
                .path("averagePrecision")).putNull("lower");
        writeReport(root, RUN_ID, report);
        JsonNode loaded = new ExternalReportService(root).getReport(RUN_ID);
        assertTrue(loaded.path("models").get(0).path("confidenceIntervals")
                .path("averagePrecision").path("lower").isNull());

        ((ObjectNode) report.withArray("models").get(0).path("confidenceIntervals"))
                .remove("recall");
        writeReport(root, RUN_ID, report);
        assertInvalid(root);
    }

    @Test
    public void invalidIdsAndMalformedCompletedReportsFailExplicitly() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("experiments");
        ExternalReportService service = new ExternalReportService(root);
        try {
            service.getReport("../secret");
            fail("Expected invalid ID rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Invalid"));
        }

        ObjectNode malformed = completeReport(RUN_ID);
        ((ObjectNode) malformed.withArray("models").get(0).path("metrics")).remove("f1");
        writeReport(root, RUN_ID, malformed);
        assertInvalid(root);
    }

    @Test
    public void rejectsSymlinkedReportFilesOutsideExperiments() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("experiments");
        Path folder = Files.createDirectories(root.resolve(RUN_ID));
        Path outside = temporary.getRoot().toPath().resolve("outside.json");
        Files.write(outside, MAPPER.writeValueAsBytes(completeReport(RUN_ID)));
        Files.createSymbolicLink(folder.resolve("external-report.json"), outside);
        try {
            new ExternalReportService(root).listReports();
            fail("Expected symlink rejection");
        } catch (ExternalReportService.InvalidReportException expected) {
            assertTrue(expected.getMessage().contains("symbolic link"));
        }
    }

    @Test
    public void rejectsTrailingGarbageAfterAValidJsonReport() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("experiments");
        Path folder = Files.createDirectories(root.resolve(RUN_ID));
        Files.writeString(folder.resolve("external-report.json"),
                MAPPER.writeValueAsString(completeReport(RUN_ID)) + " trailing");
        try {
            new ExternalReportService(root).listReports();
            fail("Expected malformed JSON rejection");
        } catch (ExternalReportService.InvalidReportException expected) {
            assertTrue(expected.getMessage().contains("Could not read external report"));
        }
    }

    @Test
    public void registersReadOnlyListDetailAndDownloadRoutes() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("experiments");
        writeReport(root, RUN_ID, completeReport(RUN_ID));
        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors ->
                    cors.addRule(rule -> rule.reflectClientOrigin = true));
        });
        ApiServer.registerExternalReportRoutes(app, new ExternalReportService(root));
        app.start(0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + app.port() + "/api/external/reports";
            HttpResponse<String> list = client.send(
                    HttpRequest.newBuilder(URI.create(base))
                            .header("Origin", "http://localhost:3000").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, list.statusCode());
            assertEquals(1, MAPPER.readTree(list.body()).path("reports").size());
            assertEquals("http://localhost:3000",
                    list.headers().firstValue("Access-Control-Allow-Origin").orElse(""));

            HttpResponse<String> detail = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/" + RUN_ID)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, detail.statusCode());
            assertEquals("complete", MAPPER.readTree(detail.body()).path("status").asText());

            HttpResponse<String> download = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/" + RUN_ID + "/download"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, download.statusCode());
            assertTrue(download.headers().firstValue("Content-Disposition").orElse("")
                    .contains("attachment"));
            assertEquals("complete", MAPPER.readTree(download.body()).path("status").asText());

            HttpResponse<String> invalid = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/../secret")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(invalid.statusCode() == 400 || invalid.statusCode() == 404);

            HttpResponse<String> missing = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/generalization-missing"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());
            assertTrue(MAPPER.readTree(missing.body()).has("error"));

            Path invalidFolder = Files.createDirectories(root.resolve("generalization-invalid"));
            Files.writeString(invalidFolder.resolve("external-report.json"), "{ invalid");
            HttpResponse<String> invalidSchema = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/generalization-invalid"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(500, invalidSchema.statusCode());
            assertTrue(MAPPER.readTree(invalidSchema.body()).has("error"));

            HttpResponse<String> invalidList = client.send(
                    HttpRequest.newBuilder(URI.create(base)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(500, invalidList.statusCode());
            assertTrue(MAPPER.readTree(invalidList.body()).has("error"));
        } finally {
            app.stop();
        }
    }

    private static void assertInvalid(Path root) throws Exception {
        try {
            new ExternalReportService(root).listReports();
            fail("Expected invalid report rejection");
        } catch (ExternalReportService.InvalidReportException expected) {
            assertTrue(expected.getMessage().contains("Invalid external report"));
        }
    }

    private static void writeReport(Path root, String id, JsonNode report) throws Exception {
        Path folder = Files.createDirectories(root.resolve(id));
        Files.write(folder.resolve("external-report.json"), MAPPER.writeValueAsBytes(report));
    }

    private static ObjectNode baseReport(String id, String status) {
        ObjectNode report = MAPPER.createObjectNode();
        report.put("schemaVersion", "sqlia-external-report-v1");
        report.put("status", status);
        report.put("reportType", "external_generalization");
        report.put("runId", id);
        report.put("createdAt", "2026-10-25T00:00:00Z");
        report.put("evaluationMode", "frozen_external_checkpoint");
        report.put("blindTestEligible", false);
        report.putArray("limitations").add("Retrospective external overlap-filtered sample.");
        report.putObject("provenance").put("source", "fixture");
        report.putObject("policy")
                .put("retrained", false)
                .put("updated", false)
                .put("thresholdsRetuned", false);
        return report;
    }

    private static ObjectNode completeReport(String id) {
        ObjectNode report = baseReport(id, "complete");
        report.put("completedAt", "2026-10-25T00:01:00Z");
        ObjectNode dataset = report.putObject("dataset");
        dataset
                .put("source", "fixture.csv")
                .put("fileSha256", "dataset-sha")
                .put("originalRows", 10)
                .put("evaluatedRows", 8)
                .put("benignCount", 4)
                .put("maliciousCount", 4)
                .put("excludedRows", 2)
                .putObject("exclusionCounts").put("overlap", 2);
        dataset.put("auditReportSha256", "audit-sha");
        var models = report.putArray("models");
        addModel(models.addObject(), "PURE_EXTENDED_ARM");
        addModel(models.addObject(), "EXTENDED_ARM");
        return report;
    }

    private static void addModel(ObjectNode model, String architecture) {
        model.put("architecture", architecture);
        model.put("checkpointSha256", "checkpoint-sha");
        model.put("threshold", 0.5);
        model.putObject("trainingPolicy").put("version", "policy-v1");
        ObjectNode metrics = model.putObject("metrics");
        metrics.put("total", 8).put("tp", 3).put("fp", 1).put("tn", 3).put("fn", 1)
                .put("accuracy", 0.75).put("precision", 0.75).put("recall", 0.75)
                .put("f1", 0.75).put("fpr", 0.25).put("averagePrecision", 0.8);
        ObjectNode intervals = model.putObject("confidenceIntervals");
        for (String metric : new String[] {
                "accuracy", "precision", "recall", "f1", "fpr", "averagePrecision"}) {
            intervals.putObject(metric)
                    .put("lower", 0.5).put("upper", 1.0)
                    .put("validReplicates", 100).put("undefinedReplicates", 0);
        }
    }
}

package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tw.edu.cse.nsysu.ArmNormalProfile;
import tw.edu.cse.nsysu.ExtendedRuleMiner;
import tw.edu.cse.nsysu.FeatureForML;
import tw.edu.cse.nsysu.api.dto.SimulationRequest;

public class SavedCheckpointEvaluationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void deploymentUsesFixedThresholdsIsolatesFeedbackAndReplaysJournal() throws Exception {
        try (Connection database = database()) {
            Path run = savedRun();
            Path journal = temporary.getRoot().toPath().resolve("feedback.db");
            double[] frozenScores;
            double[] updatedScores;
            try (var deployed = new DeployedModels(database, run, journal, temporary.getRoot().toPath())) {
                assertNotSame(deployed.frozen(), deployed.interactive());
                frozenScores = deployed.frozen().predictArchitectureProb("ordinary input", "FULL_FLOW");
                var response = new DetectionService(deployed.frozen()).detect("ordinary input");
                assertEquals(1.0, response.threshold, 0);
                assertEquals(frozenScores[1] >= 1 ? 1 : 0, response.label);
                assertEquals("frozen_deployed_checkpoint", response.modelMode);
                assertThrows(IllegalStateException.class,
                        () -> deployed.frozen().applyVerifiedFeedback("ordinary input", 0, true));
                assertThrows(IllegalStateException.class,
                        () -> deployed.frozen().updateArchitecture("ordinary input", 0, "ARM_PROFILE"));
                assertThrows(IllegalArgumentException.class,
                        () -> deployed.feedback("ordinary input", 1, true, null));
                assertEquals(0, deployed.status().get("feedbackCount"));
                for (String model : List.of("CURRENT_ONLINE_BAGGING_NB", "SGD", "HOEFFDING_TREE")) {
                    deployed.feedback("ordinary input", 0, true, model);
                }
                assertEquals(3, deployed.status().get("feedbackCount"));
                var replay = deployed.newReplaySession();
                replay.applyVerifiedFeedback("replay-only value", 0, true);
                assertEquals(3, replay.getTotalTrained());
                assertEquals(3, deployed.interactive().getTotalTrained());
                assertEquals(2, deployed.frozen().getTotalTrained());
                assertArrayEquals(frozenScores,
                        deployed.frozen().predictArchitectureProb("ordinary input", "FULL_FLOW"), 0);
                updatedScores = deployed.interactive().predictArchitectureProb("ordinary input", "FULL_FLOW");
                for (var prediction : deployed.frozen().predictAllSnapshot("ordinary input")) {
                    assertEquals(1.0, (Double) prediction.get("threshold"), 0);
                    assertEquals("frozen_deployed_checkpoint", prediction.get("modelMode"));
                }
            }
            try (var restored = new DeployedModels(database, run, journal, temporary.getRoot().toPath())) {
                assertEquals(3, restored.status().get("feedbackCount"));
                assertArrayEquals(frozenScores,
                        restored.frozen().predictArchitectureProb("ordinary input", "FULL_FLOW"), 0);
                assertArrayEquals(updatedScores,
                        restored.interactive().predictArchitectureProb("ordinary input", "FULL_FLOW"), 0);
            }
        }
    }

    @Test
    public void usesSavedThresholdsForEveryArchitectureWithoutCallingTrainer() throws Exception {
        try (Connection database = database()) {
            Path run = savedRun();
            var request = request(run);
            var service = service(database);
            var first = service.compare(request, FrozenArchitectureComparisonService.ARCHITECTURES);
            var second = service.compare(request, FrozenArchitectureComparisonService.ARCHITECTURES);
            assertEquals(5, first.size());
            var state = ModelStateService.loadFrozen(run.resolve("frozen-model.ser"));
            for (int i = 0; i < first.size(); i++) {
                var result = first.get(i);
                assertEquals("frozen_saved_checkpoint", result.get("evaluationMode"));
                assertEquals(1.0, (Double) result.get("threshold"), 0);
                assertEquals(0.25, (Double) result.get("validationF1"), 0);
                assertEquals(2, result.get("total"));
                assertEquals(123L, result.get("trainingDurationMs"));
                String architecture = (String) result.get("architecture");
                int tp = state.predictArchitectureProb("test malicious", architecture)[1] >= 1 ? 1 : 0;
                int fp = state.predictArchitectureProb("test benign", architecture)[1] >= 1 ? 1 : 0;
                assertEquals(tp, result.get("tp"));
                assertEquals(fp, result.get("fp"));
                assertEquals(1 - tp, result.get("fn"));
                assertEquals(1 - fp, result.get("tn"));
                assertEquals(result.get("testFingerprint"), second.get(i).get("testFingerprint"));
                assertEquals(hash(Files.readAllBytes(run.resolve("frozen-model.ser"))),
                        result.get("checkpointSha256"));
            }
            request.sampleSize = 1;
            assertEquals(1, service.compare(request, List.of("FULL_FLOW")).getFirst().get("total"));
            request.updateModel = true;
            assertThrows(IllegalArgumentException.class,
                    () -> service.compare(request, List.of("FULL_FLOW")));
        }
    }

    @Test
    public void rejectsChangedCheckpointReportDatasetAndUntrustedLocation() throws Exception {
        try (Connection database = database()) {
            Path run = savedRun();
            var service = service(database);
            var request = request(run);
            Path reportPath = run.resolve("training_validation.json");
            String original = Files.readString(reportPath);
            for (String field : List.of("status", "datasetFingerprint", "trainingFingerprint",
                    "validationFingerprint", "protocolVersion", "checkpointSha256")) {
                ObjectNode changed = (ObjectNode) mapper.readTree(original);
                changed.put(field, "changed");
                mapper.writeValue(reportPath.toFile(), changed);
                assertThrows(field, IllegalArgumentException.class,
                        () -> service.compare(request, List.of("FULL_FLOW")));
            }
            ObjectNode changed = (ObjectNode) mapper.readTree(original);
            ((ObjectNode) changed.path("architectures").path("FULL_FLOW")).put("threshold", 2);
            mapper.writeValue(reportPath.toFile(), changed);
            assertThrows(IllegalArgumentException.class,
                    () -> service.compare(request, List.of("FULL_FLOW")));
            Files.writeString(reportPath, original);
            Path checkpoint = run.resolve("frozen-model.ser");
            byte[] saved = Files.readAllBytes(checkpoint);
            Files.write(checkpoint, new byte[] {1, 2, 3});
            assertThrows(IllegalArgumentException.class,
                    () -> service.compare(request, List.of("FULL_FLOW")));
            Files.write(checkpoint, saved);
            try (var query = database.createStatement()) {
                query.executeUpdate("UPDATE DataTable SET source='changed' WHERE split_group='train'");
            }
            assertThrows(IllegalArgumentException.class,
                    () -> service.compare(request, List.of("FULL_FLOW")));
            request.checkpointDirectory = temporary.getRoot().getAbsolutePath();
            assertThrows(IllegalArgumentException.class,
                    () -> service.compare(request, List.of("FULL_FLOW")));
        }
    }

    private FrozenArchitectureComparisonService service(Connection connection) {
        return new FrozenArchitectureComparisonService(connection, temporary.getRoot().toPath(),
                training -> { throw new AssertionError("Saved evaluation must never train."); });
    }

    private List<FrozenArchitectureComparisonService.Row> rows() {
        var rows = new ArrayList<FrozenArchitectureComparisonService.Row>();
        for (String split : List.of("train", "validation", "test")) {
            rows.add(new FrozenArchitectureComparisonService.Row(split + " benign", 0, "fixture", split));
            rows.add(new FrozenArchitectureComparisonService.Row(split + " malicious", 1, "fixture", split));
        }
        rows.sort(Comparator.comparing(FrozenArchitectureComparisonService.Row::text));
        return rows;
    }

    private Connection database() throws Exception {
        var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var query = connection.createStatement()) {
            query.execute("CREATE TABLE DataTable(raw_sql TEXT,label INTEGER,source TEXT,split_group TEXT)");
        }
        try (var insert = connection.prepareStatement("INSERT INTO DataTable VALUES(?,?,?,?)")) {
            for (var row : rows()) {
                insert.setString(1, row.text());
                insert.setInt(2, row.label());
                insert.setString(3, row.source());
                insert.setString(4, row.split());
                insert.executeUpdate();
            }
        }
        return connection;
    }

    private Path savedRun() throws Exception {
        Path run = Files.createDirectories(temporary.getRoot().toPath().resolve("experiments/run"));
        FeatureForML features = new FeatureForML();
        features.setRules(List.of(new FeatureForML.Rule(new int[] {3}, 101, 0.8, true)));
        var rules = List.of(new ExtendedRuleMiner.ExtendedRule(new int[] {3}, false, 0.2, 0.8, 1.5));
        var model = new ModelStateService.FrozenModel(FrozenCheckpointTest.classifier(10),
                FrozenCheckpointTest.classifier(6), FrozenCheckpointTest.classifier(7),
                features, new ArmNormalProfile(), rules, 2);
        Path checkpoint = run.resolve("frozen-model.ser");
        try (var output = new ObjectOutputStream(Files.newOutputStream(checkpoint))) {
            output.writeObject(model);
        }
        ObjectNode report = mapper.createObjectNode();
        report.put("status", "complete");
        report.put("protocolVersion", "frozen-full-training-v1");
        report.put("testEvaluated", false);
        report.put("testPredictions", 0);
        report.put("reloadVerifiedOnAllValidationRows", true);
        report.put("datasetFingerprint", hash(mapper.writeValueAsBytes(rows())));
        for (String split : List.of("train", "validation")) {
            String name = split.equals("train") ? "training" : split;
            report.put(name + "Fingerprint", hash(mapper.writeValueAsBytes(
                    rows().stream().filter(r -> r.split().equals(split)).toList())));
            report.put(name + "Samples", 2);
        }
        report.put("trainingDurationMs", 123);
        report.set("trainingSettings", mapper.valueToTree(ModelStateService.frozenTrainingSettings()));
        report.put("checkpointSha256", hash(Files.readAllBytes(checkpoint)));
        var choices = report.putObject("architectures");
        for (String architecture : FrozenArchitectureComparisonService.ARCHITECTURES) {
            choices.putObject(architecture).put("threshold", 1.0).put("f1", 0.25).put("fpr", 0.75);
        }
        mapper.writeValue(run.resolve("training_validation.json").toFile(), report);
        return run;
    }

    private SimulationRequest request(Path run) throws Exception {
        Path csv = temporary.newFile().toPath();
        Files.writeString(csv, "payload,label,source,split\n"
                + "test benign,0,fixture,test\ntest malicious,1,fixture,test\n");
        SimulationRequest request = new SimulationRequest();
        request.datasetPath = csv.toString();
        request.checkpointDirectory = run.toString();
        request.sampleSize = Integer.MAX_VALUE;
        return request;
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

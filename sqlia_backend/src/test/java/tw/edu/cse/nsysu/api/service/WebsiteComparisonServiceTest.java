package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;

import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.Test;
import tw.edu.cse.nsysu.ArmNormalProfile;
import tw.edu.cse.nsysu.ExtendedRuleMiner;
import tw.edu.cse.nsysu.FeatureForML;
import tw.edu.cse.nsysu.api.dto.IncrementalFeedRequest;
import tw.edu.cse.nsysu.api.dto.SimulationRequest;

public class WebsiteComparisonServiceTest {
    @Test
    public void websiteSharesFeedbackAndReplayStateWithoutResearchArtifacts() throws Exception {
        Path work = Files.createTempDirectory(Path.of("data"), "website-test-");
        try (var database = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (var query = database.createStatement()) {
                query.execute("CREATE TABLE DataTable(raw_sql TEXT,label INTEGER,source TEXT,split_group TEXT)");
                query.execute("INSERT INTO DataTable VALUES('training benign',0,'fixture','train'),"
                        + "('training malicious',1,'fixture','train'),"
                        + "('validation benign',0,'fixture','validation')");
            }
            FeatureForML features = new FeatureForML();
            features.setRules(List.of(new FeatureForML.Rule(new int[] {3}, 101, 0.8, true)));
            var rules = List.of(new ExtendedRuleMiner.ExtendedRule(new int[] {3}, false, 0.2, 0.8, 1.5));
            Path fixture = work.resolve("fixture.ser");
            try (var stream = new ObjectOutputStream(Files.newOutputStream(fixture))) {
                stream.writeObject(new ModelStateService.FrozenModel(
                        FrozenCheckpointTest.classifier(10), FrozenCheckpointTest.classifier(6),
                        FrozenCheckpointTest.classifier(7), features, new ArmNormalProfile(), rules, 2));
            }
            ModelStateService state = ModelStateService.loadFrozen(fixture);
            state.initializeInteractive(database);
            DetectionService detection = new DetectionService(state);
            SimulationService simulation = new SimulationService(detection, state);
            WebsiteComparisonService comparison = new WebsiteComparisonService(simulation, state);
            Path csv = work.resolve("test.csv");
            Files.writeString(csv, "payload,label,source,split\n"
                    + "test benign,0,fixture,test\ntest malicious,1,fixture,test\n");
            SimulationRequest request = new SimulationRequest();
            request.datasetPath = csv.toString();
            request.sampleSize = 2;
            var first = comparison.compare(request, FrozenArchitectureComparisonService.ARCHITECTURES);
            assertEquals(5, first.size());
            assertEquals(2, state.getTotalTrained());
            for (var result : first) {
                assertEquals("interactive_website_snapshot", result.get("evaluationMode"));
                assertEquals(2, result.get("total"));
                assertFalse(result.containsKey("validationF1"));
                assertFalse(result.containsKey("checkpointSha256"));
                assertEquals("PURE_EXTENDED_ARM".equals(result.get("architecture")) ? 0.6 : 0.5,
                        (Double) result.get("threshold"), 0);
            }
            IncrementalFeedRequest feedback = new IncrementalFeedRequest();
            feedback.sql = "verified benign";
            feedback.label = 0;
            assertTrue(new IncrementalService(detection, state).process(feedback).modelUpdated);
            assertEquals(3, state.getTotalTrained());
            assertEquals("interactive_training", detection.detect("test benign").modelMode);
            assertEquals(0.5, detection.detect("test benign").threshold, 0);
            request.architecture = "EXTENDED_ARM";
            request.updateModel = true;
            assertEquals(2, simulation.run(request).total);
            assertEquals(5, state.getTotalTrained());
            request.updateModel = false;
            assertEquals(5, comparison.compare(request, List.of("FULL_FLOW"))
                    .getFirst().get("trainingSamples"));
            request.checkpointDirectory = "not-a-website-option";
            assertThrows(IllegalArgumentException.class, () -> comparison.compare(request, List.of("FULL_FLOW")));
            Files.writeString(csv, "payload,label,source,split\ntraining benign,0,fixture,test\n");
            request.checkpointDirectory = null;
            assertThrows(IllegalArgumentException.class, () -> comparison.compare(request, List.of("FULL_FLOW")));
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

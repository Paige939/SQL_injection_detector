package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;

import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.List;
import org.junit.Test;
import tw.edu.cse.nsysu.ArmNormalProfile;
import tw.edu.cse.nsysu.ExtendedRuleMiner.ExtendedRule;
import tw.edu.cse.nsysu.FeatureForML;

public class WebsitePureArmCalibrationTest {
    @Test
    public void calibrationUsesValidationOnlyAndLeavesOtherModelsUnchanged() throws Exception {
        var fixture = Files.createTempFile("website-calibration-", ".ser");
        try (var database = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var features = new FeatureForML();
            features.setRules(List.of());
            try (var output = new ObjectOutputStream(Files.newOutputStream(fixture))) {
                output.writeObject(new ModelStateService.FrozenModel(
                        FrozenCheckpointTest.classifier(10), FrozenCheckpointTest.classifier(6),
                        FrozenCheckpointTest.classifier(7), features, new ArmNormalProfile(),
                        List.of(new ExtendedRule(new int[] {25}, true, 0.2, 0.66, 1.5),
                                new ExtendedRule(new int[] {3}, true, 0.2, 0.99, 2)), 2));
            }
            try (var query = database.createStatement()) {
                query.execute("CREATE TABLE DataTable(raw_sql TEXT,label INTEGER,source TEXT,split_group TEXT)");
                query.execute("INSERT INTO DataTable VALUES"
                        + "('training input',0,'fixture','train'),"
                        + "('SELECT name WHERE id = 4',0,'fixture','validation'),"
                        + "('OR 1=1',1,'fixture','validation'),"
                        + "(NULL,NULL,'fixture','test')");
            }
            var state = ModelStateService.loadFrozen(fixture);
            state.initializeInteractive(database);
            assertEquals(1, state.predictArchitecture("SELECT name WHERE id = 4", "PURE_EXTENDED_ARM"));
            state.calibrateWebsitePureArmThreshold();
            assertEquals(Math.nextUp(0.66), state.getArchitectureThresholds().get("PURE_EXTENDED_ARM"), 0);
            assertEquals(0, state.predictArchitecture("SELECT name WHERE id = 4", "PURE_EXTENDED_ARM"));
            assertEquals(1, state.predictArchitecture("OR 1=1", "PURE_EXTENDED_ARM"));
            assertEquals("review", state.predictSnapshot("SELECT name WHERE id = 4",
                    List.of("PURE_EXTENDED_ARM")).getFirst().get("action"));
            assertEquals(2, state.getTotalTrained());
            assertEquals("interactive_training", state.predictSnapshot("OR 1=1",
                    List.of("PURE_EXTENDED_ARM")).getFirst().get("modelMode"));
            for (String architecture : List.of("ARM_PROFILE", "FULL_FLOW", "RATIO_INCREMENTAL_ML", "EXTENDED_ARM")) {
                assertEquals(0.5, state.getArchitectureThresholds().get(architecture), 0);
            }
            try (var query = database.createStatement()) {
                query.execute("UPDATE DataTable SET label=0 WHERE split_group='validation'");
                assertThrows(IllegalArgumentException.class, state::calibrateWebsitePureArmThreshold);
                query.execute("UPDATE DataTable SET label=NULL WHERE split_group='validation'");
                assertThrows(IllegalArgumentException.class, state::calibrateWebsitePureArmThreshold);
                query.execute("DELETE FROM DataTable WHERE split_group='validation'");
                assertThrows(IllegalArgumentException.class, state::calibrateWebsitePureArmThreshold);
                query.execute("INSERT INTO DataTable VALUES('training input',1,'fixture','validation')");
                assertThrows(IllegalArgumentException.class, state::calibrateWebsitePureArmThreshold);
            }
            assertEquals(Math.nextUp(0.66), state.getArchitectureThresholds().get("PURE_EXTENDED_ARM"), 0);
        } finally {
            Files.deleteIfExists(fixture);
        }
    }
}

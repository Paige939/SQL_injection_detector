package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class DefenseModelsTest {
    @Test public void fixedModelsUseExtendedFeaturesAndEvaluateAllVariantsWithoutUpdates() throws Exception {
        Path work = Files.createTempDirectory(Path.of("data"), "defense-test-");
        Path storeFile = Files.createTempFile("defense-models-", ".db");
        try (var training = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (var query = training.createStatement()) {
                query.execute("CREATE TABLE DataTable(id INTEGER PRIMARY KEY,raw_sql TEXT,label INTEGER,"
                        + "split_group TEXT,batch_seq INTEGER)");
            }
            try (var insert = training.prepareStatement("INSERT INTO DataTable VALUES(?,?,?,'train',1)")) {
                for (int i = 0; i < 40; i++) {
                    insert.setInt(1, i + 1);
                    insert.setString(2, i % 2 == 0 ? "product training " + i : "' OR " + i + "=" + i + " --");
                    insert.setInt(3, i % 2);
                    insert.executeUpdate();
                }
            }
            var base = ModelStateService.TrainingSettings.defaults();
            var defaults = new ModelStateService.TrainingSettings(base.trainingOrderSeed(), base.ensembleSize(),
                    base.fullFlowSeed(), base.ratioSeed(), base.extendedSeed(), base.standardMinSupport(),
                    base.standardMinConfidence(), base.extendedMinSupport(), base.extendedMinConfidence(),
                    base.extendedMinLift(), base.extendedMaxLength(), base.extendedMaxRulesPerFamily(), work);
            var website = new ModelStateService(training, 20, defaults);
            var models = new DefenseModels(website, training);
            assertEquals(20, models.trainingSamples());
            var before = new java.util.ArrayList<DefenseService.Prediction>();
            for (String architecture : FrozenArchitectureComparisonService.ARCHITECTURES) {
                before.add(models.predict("Keyboard", architecture, IncrementalAlgorithm.CURRENT_ONLINE_BAGGING_NB));
            }
            for (var algorithm : IncrementalAlgorithm.available()) {
                var prediction = models.predict("Keyboard", "EXTENDED_ARM", algorithm);
                assertTrue(Double.isFinite(prediction.score()));
                assertEquals(0.5, prediction.threshold(), 0);
                before.add(prediction);
            }
            for (int i = 0; i < 10; i++) website.updateArchitecture("Keyboard", 1, "EXTENDED_ARM");
            var after = new java.util.ArrayList<DefenseService.Prediction>();
            for (String architecture : FrozenArchitectureComparisonService.ARCHITECTURES) {
                after.add(models.predict("Keyboard", architecture, IncrementalAlgorithm.CURRENT_ONLINE_BAGGING_NB));
            }
            for (var algorithm : IncrementalAlgorithm.available()) {
                after.add(models.predict("Keyboard", "EXTENDED_ARM", algorithm));
            }
            assertEquals(before, after);
            var csv = work.resolve("test.csv");
            Files.writeString(csv, "payload,label,split\nKeyboard,0,test\n' UNION SELECT hidden --,1,test\n");
            try (var store = new DefenseStore(storeFile)) {
                var evaluation = new DefenseEvaluation(models, training, csv, store);
                var request = new DefenseEvaluation.Request();
                request.sampleSize = 2;
                var report = evaluation.run(request);
                assertEquals(7, ((List<?>) report.get("results")).size());
                assertEquals(false, report.get("updatesEnabled"));
                assertEquals(0, report.get("businessQueriesExecuted"));
                assertEquals(0, store.recent(200).size());
                assertEquals("complete", store.latestReport().get("status").asText());
                request.extendedOnly = true;
                assertEquals(4, ((List<?>) evaluation.run(request).get("results")).size());
                assertEquals(30, website.getTotalTrained());
                Files.writeString(csv, "payload,label,split\nproduct training 0,0,test\nnovel,1,test\n");
                assertThrows(IllegalArgumentException.class, () -> evaluation.run(request));
            }
        } finally {
            Files.deleteIfExists(storeFile);
            try (var paths = Files.walk(work)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

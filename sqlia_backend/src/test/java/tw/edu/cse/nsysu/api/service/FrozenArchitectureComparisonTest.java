package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tw.edu.cse.nsysu.api.dto.SimulationRequest;

public class FrozenArchitectureComparisonTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void thresholdsOptimizeValidationWithTiesAndExactBoundary() {
        var choice = ValidationThreshold.select(List.of(
                new ValidationThreshold.Scored(0.7, 0),
                new ValidationThreshold.Scored(0.8, 1),
                new ValidationThreshold.Scored(0.9, 1)));
        assertEquals(1, choice.f1(), 0);
        assertEquals(Math.nextUp(0.7), choice.threshold(), 0);
        assertEquals(0, choice.fpr(), 0);
        var ties = ValidationThreshold.select(List.of(
                new ValidationThreshold.Scored(0.5, 0),
                new ValidationThreshold.Scored(0.5, 1)));
        assertEquals(0, ties.threshold(), 0);
        assertThrows(IllegalArgumentException.class, () -> ValidationThreshold.select(List.of()));
        assertThrows(IllegalArgumentException.class, () -> ValidationThreshold.select(List.of(
                new ValidationThreshold.Scored(0.5, 0))));
        assertThrows(IllegalStateException.class, () -> ValidationThreshold.requireScore(Double.NaN));
        assertThrows(IllegalStateException.class, () -> ValidationThreshold.requireScore(1.01));
    }

    @Test
    public void snapshotRejectsMixedSplitGroupsDuplicatesAndMissingClasses() {
        var valid = fixture();
        FrozenArchitectureComparisonService.validateSnapshot(valid);
        var overlap = new ArrayList<>(valid);
        overlap.set(2, row("TRAIN%20benign", 0, "validation"));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenArchitectureComparisonService.validateSnapshot(overlap));
        var duplicate = new ArrayList<>(valid);
        duplicate.add(valid.getFirst());
        assertThrows(IllegalArgumentException.class,
                () -> FrozenArchitectureComparisonService.validateSnapshot(duplicate));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenArchitectureComparisonService.validateSnapshot(valid.subList(0, 3)));
    }

    @Test
    public void fullTrainingIsolatedTuningPrecedesTestAndRepeatedRunsCreateFreshState() throws Exception {
        try (Connection database = database()) {
            Path csv = testCsv();
            AtomicInteger trainingCalls = new AtomicInteger();
            AtomicInteger validationCalls = new AtomicInteger();
            FrozenArchitectureComparisonService service = new FrozenArchitectureComparisonService(
                    database, temporary.getRoot().toPath(), training -> {
                        trainingCalls.incrementAndGet();
                        validationCalls.set(0);
                        assertNotSame(database, training);
                        try (var query = training.createStatement();
                                var rows = query.executeQuery("SELECT COUNT(*),MIN(split_group),MAX(split_group) FROM DataTable")) {
                            assertTrue(rows.next());
                            assertEquals(2, rows.getInt(1));
                            assertEquals("train", rows.getString(2));
                            assertEquals("train", rows.getString(3));
                        }
                        return (input, architecture) -> {
                            if (input.startsWith("validation")) validationCalls.incrementAndGet();
                            else {
                                assertTrue(input.startsWith("test"));
                                assertEquals(10, validationCalls.get());
                            }
                            // A threshold of 0.5 would misclassify both benign examples.
                            return input.endsWith("benign") ? 0.7 : 0.9;
                        };
                    });
            SimulationRequest request = request(csv);
            var first = service.compare(request, FrozenArchitectureComparisonService.ARCHITECTURES);
            var second = service.compare(request, FrozenArchitectureComparisonService.ARCHITECTURES);
            assertEquals(2, trainingCalls.get());
            assertEquals(5, first.size());
            for (int i = 0; i < 5; i++) {
                Map<String, Object> result = first.get(i);
                assertEquals(2, result.get("trainingSamples"));
                assertEquals(2, result.get("validationSamples"));
                assertEquals(2, result.get("total"));
                assertEquals(1, result.get("tp"));
                assertEquals(1, result.get("tn"));
                assertEquals(0, result.get("fp"));
                assertEquals(0, result.get("fn"));
                assertEquals(1.0, (Double) result.get("f1"), 0);
                assertEquals(Math.nextUp(0.7), (Double) result.get("threshold"), 0);
                assertEquals("frozen_full_training", result.get("evaluationMode"));
                assertEquals(result.get("datasetFingerprint"), second.get(i).get("datasetFingerprint"));
                assertEquals(result.get("testFingerprint"), second.get(i).get("testFingerprint"));
                assertFalse(((Map<?, ?>) result.get("bySource")).isEmpty());
                assertFalse(((Map<?, ?>) result.get("byInputType")).isEmpty());
            }
            try (var query = database.createStatement();
                    var rows = query.executeQuery("SELECT COUNT(*) FROM DataTable")) {
                assertTrue(rows.next());
                assertEquals(6, rows.getInt(1));
            }
        }
    }

    @Test
    public void passesAllTrainingRowsBeyondOldWarmupLimitAndRejectsNonfiniteScores() throws Exception {
        try (Connection database = database()) {
            try (var insert = database.prepareStatement("INSERT INTO DataTable VALUES(?,?,?,'train')")) {
                for (int i = 0; i < 2001; i++) {
                    insert.setString(1, "extra training value " + i);
                    insert.setInt(2, i % 2);
                    insert.setString(3, "fixture");
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            var service = new FrozenArchitectureComparisonService(database,
                    temporary.getRoot().toPath(), training -> {
                        try (var query = training.createStatement();
                                var rows = query.executeQuery("SELECT COUNT(*) FROM DataTable")) {
                            assertTrue(rows.next());
                            assertEquals(2003, rows.getInt(1));
                        }
                        return (input, architecture) -> Double.NaN;
                    });
            assertThrows(IllegalStateException.class, () -> service.compare(request(testCsv()),
                    FrozenArchitectureComparisonService.ARCHITECTURES));
        }
    }

    @Test
    public void changingOnlyTestLabelsCannotChangeValidationThresholds() throws Exception {
        try (Connection database = database()) {
            var service = new FrozenArchitectureComparisonService(database,
                    temporary.getRoot().toPath(), training -> (input, architecture) ->
                            input.endsWith("benign") ? 0.7 : 0.9);
            var architectures = List.of("FULL_FLOW");
            var first = service.compare(request(testCsv()), architectures).getFirst();
            try (var statement = database.createStatement()) {
                statement.executeUpdate("UPDATE DataTable SET label=1-label WHERE split_group='test'");
            }
            Path reversed = temporary.newFile().toPath();
            Files.writeString(reversed, "payload,label,source,split\n"
                    + "test benign,1,fixture,test\ntest malicious,0,fixture,test\n");
            var second = service.compare(request(reversed), architectures).getFirst();
            assertEquals(first.get("threshold"), second.get("threshold"));
            assertEquals(first.get("validationF1"), second.get("validationF1"));
            assertNotEquals(first.get("f1"), second.get("f1"));
            assertNotEquals(first.get("datasetFingerprint"), second.get("datasetFingerprint"));
        }
    }

    @Test
    public void rejectsOldIncompleteUnlabelledOrMismatchedTestBeforeTraining() throws Exception {
        try (Connection database = database()) {
            AtomicInteger calls = new AtomicInteger();
            var service = new FrozenArchitectureComparisonService(database,
                    temporary.getRoot().toPath(), training -> {
                        calls.incrementAndGet();
                        return (input, architecture) -> 0.5;
                    });
            for (String content : List.of(
                    "payload,label,source\ntest benign,0,fixture\n",
                    "payload,label,source,split\ntest benign,0,fixture,test\n",
                    "payload,label,source,split\ntrain benign,0,fixture,test\n",
                    "payload,label,source,split\ntest benign,1,fixture,test\n",
                    "payload,label,source,split\ntest benign,0,fixture,validation\n",
                    "payload,label,source,split\ntest benign,0,fixture,test\ntest benign,0,fixture,test\n")) {
                Path csv = temporary.newFile().toPath();
                Files.writeString(csv, content);
                assertThrows(IllegalArgumentException.class,
                        () -> service.compare(request(csv), FrozenArchitectureComparisonService.ARCHITECTURES));
            }
            var updates = request(testCsv());
            updates.updateModel = true;
            assertThrows(IllegalArgumentException.class,
                    () -> service.compare(updates, FrozenArchitectureComparisonService.ARCHITECTURES));
            assertEquals(0, calls.get());
        }
    }

    private static List<FrozenArchitectureComparisonService.Row> fixture() {
        return List.of(row("train benign", 0, "train"), row("train malicious", 1, "train"),
                row("validation benign", 0, "validation"), row("validation malicious", 1, "validation"),
                row("test benign", 0, "test"), row("test malicious", 1, "test"));
    }

    private static FrozenArchitectureComparisonService.Row row(String text, int label, String split) {
        return new FrozenArchitectureComparisonService.Row(text, label, "fixture", split);
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var query = connection.createStatement()) {
            query.execute("CREATE TABLE DataTable(raw_sql TEXT,label INTEGER,source TEXT,split_group TEXT)");
        }
        try (var insert = connection.prepareStatement("INSERT INTO DataTable VALUES(?,?,?,?)")) {
            for (var row : fixture()) {
                insert.setString(1, row.text());
                insert.setInt(2, row.label());
                insert.setString(3, row.source());
                insert.setString(4, row.split());
                insert.executeUpdate();
            }
        }
        return connection;
    }

    private Path testCsv() throws Exception {
        Path path = temporary.newFile().toPath();
        Files.writeString(path, "payload,label,source,split\n"
                + "test benign,0,fixture,test\ntest malicious,1,fixture,test\n");
        return path;
    }

    private static SimulationRequest request(Path path) {
        SimulationRequest request = new SimulationRequest();
        request.datasetPath = path.toString();
        request.sampleSize = Integer.MAX_VALUE;
        return request;
    }
}

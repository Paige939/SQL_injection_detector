package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;
import tw.edu.cse.nsysu.preprocess.DatasetPreprocessor;
import tw.edu.cse.nsysu.preprocess.PayloadNormalizer;
import tw.edu.cse.nsysu.preprocess.RawRecord;

public class ResearchExperimentServiceTest {
    @Test
    public void finiteGridUsesValidationOnlyAndExcludesSharedAndNearHeldOutOverlaps() throws Exception {
        Path output = Path.of("target/research-experiment-test");
        deleteTree(output);
        Files.createDirectories(output);
        try {
            var sources = DatasetPreprocessor.defaultSources().stream()
                    .map(spec -> spec.sourceName()).toList();
            String a = sources.get(0), b = sources.get(1), c = sources.get(2);
            List<RawRecord> raw = new ArrayList<>();
            addSourceRows(raw, a, "a");
            addSourceRows(raw, b, "b");
            addSourceRows(raw, c, "heldout-c");
            raw.add(new RawRecord("SELECT 1 /* shared exact provenance */", "0", a));
            raw.add(new RawRecord("SELECT 1 /* shared exact provenance */", "0", c));
            raw.add(new RawRecord("select * from users where id = 1", "1", a));
            raw.add(new RawRecord("SELECT   * FROM USERS WHERE ID = 1", "1", c));

            List<ResearchExperimentService.Sample> training =
                    mergedRows("merged-training", "train", 40);
            List<ResearchExperimentService.Sample> validation =
                    mergedRows("merged-validation", "validation", 20);
            List<Integer> trainingSizes = new ArrayList<>();
            ResearchExperimentService service = new ResearchExperimentService((connection, settings) -> {
                int rows;
                try (var query = connection.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM DataTable WHERE split_group='train'")) {
                    query.next();
                    rows = query.getInt(1);
                }
                trainingSizes.add(rows);
                return new ResearchExperimentService.ExperimentModel() {
                    @Override
                    public double probability(String text, String architecture) {
                        return text.contains("-sqli-") ? 0.9 : 0.1;
                    }

                    @Override
                    public void save(Path path) throws Exception {
                        Files.writeString(path, settings.ensembleSize() + ":" + settings.extendedMaxLength());
                    }
                };
            });

            Path reportPath = service.run(raw, training, validation, output);
            JsonNode report = new ObjectMapper().readTree(reportPath.toFile());
            assertEquals("complete", report.path("status").asText());
            assertEquals("sqlia-research-results-v1", report.path("schemaVersion").asText());
            assertEquals(4, report.path("grid").size());
            assertFalse(report.path("protocol").path("mergedTestEvaluated").asBoolean());
            assertFalse(report.path("protocol").path("mergedTestPartitionRead").asBoolean());
            assertEquals(4 + sources.size() * 4, trainingSizes.size());

            JsonNode sourceHoldout = null;
            for (JsonNode protocol : report.path("crossDataset")) {
                if (c.equals(protocol.path("heldOutSource").asText())) sourceHoldout = protocol;
            }
            assertTrue(sourceHoldout != null);
            assertEquals(a + "+" + b + "->" + c, sourceHoldout.path("protocol").asText());
            assertTrue(sourceHoldout.path("sharedExactProvenanceExcludedFromHeldOut").asInt() >= 1);
            assertTrue(sourceHoldout.path("nearOverlapVariantsExcludedFromHeldOut").asInt() >= 1);
            assertEquals(0, sourceHoldout.path("groupOverlapCount").asInt());
            assertEquals(1, sourceHoldout.path("evaluationPassesPerArchitecture").asInt());
            for (String architecture : ResearchExperimentService.ARCHITECTURES) {
                assertTrue(sourceHoldout.path("architectures").path(architecture).has("heldOut"));
                assertTrue(sourceHoldout.path("architectures").path(architecture)
                        .path("heldOut").has("f1"));
                assertTrue(Files.isRegularFile(output.resolve("artifacts/cross-dataset/")
                        .resolve(a + "_" + b + "-to-" + c).resolve(architecture + ".ser")));
            }
            assertFalse(report.path("mergedValidationTuning").path("testPartitionRead").asBoolean());
            assertEquals(0, report.path("mergedValidationTuning").path("testPredictions").asInt());
            assertTrue(Files.isRegularFile(output.resolve("artifacts/merged/ARM_PROFILE.ser")));
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void incompleteHoldoutIsReportedRatherThanSilentlySubstituted() throws Exception {
        Path output = Path.of("target/research-incomplete-test");
        deleteTree(output);
        Files.createDirectories(output);
        try {
            var sources = DatasetPreprocessor.defaultSources().stream()
                    .map(spec -> spec.sourceName()).toList();
            String a = sources.get(0), b = sources.get(1), c = sources.get(2);
            List<RawRecord> raw = new ArrayList<>();
            addSourceRows(raw, a, "a");
            addSourceRows(raw, b, "b");
            for (int i = 0; i < 20; i++) raw.add(new RawRecord("heldout-c-benign-" + i, "0", c));
            List<ResearchExperimentService.Sample> training = mergedRows("train", "train", 40);
            List<ResearchExperimentService.Sample> validation = mergedRows("validation", "validation", 20);
            ResearchExperimentService service = new ResearchExperimentService((connection, settings) ->
                    new ResearchExperimentService.ExperimentModel() {
                        @Override
                        public double probability(String text, String architecture) {
                            return text.contains("-sqli-") ? 0.9 : 0.1;
                        }

                        @Override
                        public void save(Path path) throws Exception {
                            Files.writeString(path, "synthetic");
                        }
                    });

            JsonNode report = new ObjectMapper().readTree(service.run(raw, training, validation, output).toFile());
            JsonNode cResult = null;
            for (JsonNode protocol : report.path("crossDataset")) {
                if (c.equals(protocol.path("heldOutSource").asText())) cResult = protocol;
            }
            assertTrue(cResult != null);
            assertEquals("unidentifiable", cResult.path("status").asText());
            assertTrue(cResult.path("incompleteReason").asText().contains("lacks one or both labels"));
            assertEquals(0, cResult.path("evaluationPassesPerArchitecture").asInt());
            assertEquals(20, cResult.path("heldOutLabelCounts").path("0").asInt());
            assertEquals(0, cResult.path("heldOutLabelCounts").path("1").asInt());
            assertEquals(0, cResult.path("architectures").size());
        } finally {
            deleteTree(output);
        }
    }

    private static List<ResearchExperimentService.Sample> mergedRows(
            String prefix, String split, int groups) {
        List<ResearchExperimentService.Sample> rows = new ArrayList<>();
        for (int i = 0; i < groups; i++) {
            int label = i % 2;
            String text = prefix + (label == 1 ? "-sqli-" : "-benign-") + i;
            rows.add(new ResearchExperimentService.Sample(text, label, "merged", split,
                    PayloadNormalizer.groupKey(text)));
        }
        return rows;
    }

    private static void addSourceRows(List<RawRecord> rows, String source, String prefix) {
        for (int i = 0; i < 40; i++) {
            int label = i % 2;
            rows.add(new RawRecord(prefix + "-" + source + (label == 1 ? "-sqli-" : "-benign-") + i,
                    Integer.toString(label), source));
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

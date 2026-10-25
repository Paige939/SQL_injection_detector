package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;

public class GeneralizationPipelineTest {
    private Path root;
    @Before public void setup() throws Exception {
        root = Files.createDirectory(Path.of("target", "generalization-test-" + UUID.randomUUID()));
        Files.createDirectories(root.resolve("data/raw"));
    }
    @After public void cleanup() throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private void fixture(String external) throws Exception {
        Files.writeString(root.resolve("data/raw/Modified_SQL_Dataset.csv"),
                "Query,Label\nold,0\n' OR 1=1 --,1\nconflict,0\nSELECT alpha FROM beta,0\n"
                        + "quarantine,invalid\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("data/raw/SQLiV3.csv"), "Sentence,Label\nconflict,1\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("data/raw/sqli.csv"), "Sentence,Label\nwide,0,extra\n", StandardCharsets.UTF_16);
        Files.writeString(root.resolve("data/raw/clean_sql_dataset.csv"), external, StandardCharsets.UTF_8);
    }
    @Test public void auditsActualRecordsAndPreservesOldExportIndependence() throws Exception {
        fixture("Query,Label\n\"SELECT\nalpha FROM beta\",0\nold,1\nquarantine,0\n"
                + "benign unique phrase,0\n\"UNION SELECT 5,6\",1\n\"UNION SELECT 5,6\",1\n"
                + "missing,\n,0\ninvalid,2\nbad,0,extra\nsame,0\nsame,1\n");
        Path output = root.resolve("run");
        String original = GeneralizationAudit.sha(root.resolve("data/raw/clean_sql_dataset.csv"));
        GeneralizationAudit.run(root, output);
        Map<String, Object> audit = GeneralizationAudit.object(output.resolve("audit-report.json"));
        assertEquals("complete", audit.get("status"));
        assertEquals(12, ((Number) audit.get("originalRows")).intValue());
        assertEquals(false, audit.get("blindTestEligible"));
        Map<?, ?> counts = (Map<?, ?>) audit.get("exclusionCounts");
        assertTrue(counts.containsKey("old_template_overlap"));
        assertTrue(counts.containsKey("old_normalized_overlap"));
        assertTrue(counts.containsKey("blank_label"));
        assertTrue(counts.containsKey("width_error"));
        assertTrue(counts.containsKey("conflicting_exact_labels"));
        assertEquals(2, ((Number) counts.get("duplicate_exact")).intValue());
        String oldExport = Files.readString(output.resolve("old-valid.csv"));
        assertTrue("External conflicting label must not change old training", oldExport.contains("old,0,"));
        assertFalse("Old conflict rows quarantined", oldExport.contains("conflict,"));
        assertEquals(original, GeneralizationAudit.sha(root.resolve("data/raw/clean_sql_dataset.csv")));
        String reportHash = GeneralizationAudit.sha(output.resolve("audit-report.json"));
        assertThrows(FileAlreadyExistsException.class, () -> GeneralizationAudit.run(root, output));
        assertEquals(reportHash, GeneralizationAudit.sha(output.resolve("audit-report.json")));
    }
    @Test public void malformedQuotedCsvFailsClosedAndPersistsFailure() throws Exception {
        fixture("Query,Label\n\"unclosed,1\n");
        Path output = root.resolve("run");
        assertThrows(Exception.class, () -> GeneralizationAudit.run(root, output));
        assertEquals("failed", GeneralizationAudit.object(output.resolve("audit-report.json")).get("status"));
        assertFalse(Files.exists(output.resolve("external-valid.csv")));
    }
    @Test public void templateIdentifiersAndLiteralsAreExplicitHeuristics() {
        assertEquals(GeneralizationAudit.templateKey("SELECT foo FROM accounts WHERE id=42"),
                GeneralizationAudit.templateKey("select bar from people where x=19"));
        assertEquals(GeneralizationAudit.templateKey("name='alice'"),
                GeneralizationAudit.templateKey("account='bob'"));
        assertNotEquals(GeneralizationAudit.templateKey("SELECT foo FROM accounts"),
                GeneralizationAudit.templateKey("DELETE FROM accounts"));
    }
    @Test public void tiedAveragePrecisionIsJointAndInvariantToOrder() {
        var rows = List.of(new GeneralizationMetrics.Scored(.9, 1, "s", "a"),
                new GeneralizationMetrics.Scored(.9, 0, "s", "b"),
                new GeneralizationMetrics.Scored(.1, 1, "s", "c"));
        Map<String, Object> metrics = GeneralizationMetrics.metrics(rows, .5);
        assertEquals(7.0 / 12, ((Number) metrics.get("averagePrecision")).doubleValue(), 1e-12);
        assertEquals(1L, metrics.get("tp")); assertEquals(1L, metrics.get("fp"));
        assertEquals(1L, metrics.get("fn")); assertEquals(0L, metrics.get("tn"));
        List<GeneralizationMetrics.Scored> reversed = new ArrayList<>(rows); Collections.reverse(reversed);
        assertEquals(metrics, GeneralizationMetrics.metrics(reversed, .5));
    }
    @Test public void clusterBootstrapIsDeterministicAndCountsDegenerateReplicates() {
        var rows = List.of(new GeneralizationMetrics.Scored(.9, 1, "s", "a"),
                new GeneralizationMetrics.Scored(.1, 0, "s", "b"));
        var ci = GeneralizationMetrics.intervals(rows, .5, 200, 42);
        assertEquals(ci, GeneralizationMetrics.intervals(rows, .5, 200, 42));
        Map<?, ?> ap = (Map<?, ?>) ci.get("averagePrecision");
        assertTrue(((Number) ap.get("undefinedReplicates")).intValue() > 0);
        assertEquals(200, ((Number) ap.get("validReplicates")).intValue()
                + ((Number) ap.get("undefinedReplicates")).intValue());
        assertThrows(IllegalArgumentException.class, () -> GeneralizationMetrics.intervals(rows, .5, 199, 42));
        var negatives = List.of(new GeneralizationMetrics.Scored(.1, 0, "s", "a"));
        assertNull(GeneralizationMetrics.metrics(negatives, .5).get("averagePrecision"));
    }
    @Test public void optimizedBootstrapMatchesPhysicalClusterResamplingWithTies() {
        var rows = List.of(new GeneralizationMetrics.Scored(.9, 1, "s", "a"),
                new GeneralizationMetrics.Scored(.9, 0, "s", "a"),
                new GeneralizationMetrics.Scored(.2, 1, "s", "b"),
                new GeneralizationMetrics.Scored(.1, 0, "s", "c"));
        Random random = new Random(42);
        List<Double> reference = new ArrayList<>();
        for (int r = 0; r < 200; r++) {
            List<GeneralizationMetrics.Scored> sample = new ArrayList<>();
            for (int g = 0; g < 3; g++) {
                String chosen = List.of("a","b","c").get(random.nextInt(3));
                rows.stream().filter(row -> row.group().equals(chosen)).forEach(sample::add);
            }
            Number value = (Number) GeneralizationMetrics.metrics(sample, .5).get("averagePrecision");
            if (value != null) reference.add(value.doubleValue());
        }
        Collections.sort(reference);
        Map<?, ?> ap = (Map<?, ?>) GeneralizationMetrics.intervals(rows, .5, 200, 42).get("averagePrecision");
        assertEquals(reference.size(), ((Number) ap.get("validReplicates")).intValue());
        for (var bound : Map.of("lower", .025, "upper", .975).entrySet()) {
            double at = bound.getValue() * (reference.size() - 1);
            int lo = (int) at, hi = (int) Math.ceil(at);
            double expected = reference.get(lo) + (reference.get(hi) - reference.get(lo)) * (at - lo);
            assertEquals(expected, ((Number) ap.get(bound.getKey())).doubleValue(), 1e-14);
        }
    }
    @Test public void calibrationUsesRawScoresWithoutRefitting() {
        var rows = List.of(new GeneralizationMetrics.Scored(.8, 1, "s", "a"),
                new GeneralizationMetrics.Scored(.2, 0, "s", "b"));
        Map<String, Object> metrics = GeneralizationRunner.calibration(rows);
        assertEquals(.04, ((Number) metrics.get("brierScore")).doubleValue(), 1e-12);
        assertEquals(.2, ((Number) metrics.get("expectedCalibrationError10Bins")).doubleValue(), 1e-12);
        assertEquals(.5, ((Number) metrics.get("meanMaliciousScore")).doubleValue(), 1e-12);
        assertEquals(.5, ((Number) metrics.get("positivePrevalence")).doubleValue(), 1e-12);
        assertEquals(10, ((List<?>) metrics.get("bins")).size());
    }
    @Test public void evaluationCannotFallbackWithoutFrozenManifestAndNeverOverwrites() throws Exception {
        fixture("Query,Label\nordinary words,0\nUNION SELECT 1,1\n");
        Path output = root.resolve("run");
        GeneralizationAudit.run(root, output);
        assertThrows(Exception.class, () -> GeneralizationRunner.evaluate(root, output));
        Path report = output.resolve("external-report.json");
        Map<String, Object> failed = GeneralizationAudit.object(report);
        assertEquals("failed", failed.get("status"));
        assertEquals("frozen_external_checkpoint", failed.get("evaluationMode"));
        String hash = GeneralizationAudit.sha(report);
        assertThrows(FileAlreadyExistsException.class, () -> GeneralizationRunner.evaluate(root, output));
        assertEquals(hash, GeneralizationAudit.sha(report));
    }
}

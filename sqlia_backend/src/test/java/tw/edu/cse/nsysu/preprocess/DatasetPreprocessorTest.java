package tw.edu.cse.nsysu.preprocess;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DatasetPreprocessorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void previewEntryPointRequiresExplicitPathsAndRefusesExistingDirectories() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> DatasetPreprocessor.main(new String[0]));
        Path existing = temporary.newFolder("preserved").toPath();
        Path marker = existing.resolve("keep.txt");
        Files.writeString(marker, "preserve");
        assertThrows(IOException.class, () -> DatasetPreprocessor.main(
                new String[] {Path.of(".").toAbsolutePath().toString(), existing.toString()}));
        assertEquals("preserve", Files.readString(marker));
        assertEquals(1, existing.toFile().list().length);
    }

    @Test
    public void previewEntryPointExportsOnlyToNewDirectory() throws Exception {
        Path backend = temporary.newFolder("fixture backend").toPath();
        Files.createDirectories(backend.resolve("data/raw"));
        Files.createDirectories(backend.resolve("db"));
        Path database = backend.resolve("db/SQLIA.db");
        Files.writeString(database, "do not open or replace");
        for (var source : DatasetPreprocessor.defaultSources()) {
            Files.writeString(backend.resolve(source.path()),
                    source.textColumn() + "," + source.labelColumn() + "\nordinary input,0\n");
        }
        Path output = backend.resolve("data/processed/new preview");
        DatasetPreprocessor.main(new String[] {backend.toString(), output.toString()});
        assertTrue(Files.exists(output.resolve("combined_preprocessed.csv")));
        assertTrue(Files.exists(output.resolve("quarantine.csv")));
        assertFalse(Files.exists(backend.resolve("data/processed/combined_preprocessed.csv")));
        assertEquals("do not open or replace", Files.readString(database));
    }

    @Test
    public void preservesBenignValuesQuarantinesConflictsAndExportsProvenance() throws Exception {
        Path input = temporary.getRoot().toPath().resolve("source.csv");
        Files.writeString(input, "Query,Label\n"
                + "47209, 0 \nexample@example.com,0\nordinary words,0\n"
                + "SELECT 1,0\nSELECT 1,0\n select 1 ,1\n"
                + "ambiguous,0\nambiguous,1\n,1\nbad label,2\n");
        Path output = temporary.newFolder("output").toPath();
        var result = DatasetPreprocessor.prepare(List.of(
                new CsvUnifier.SourceSpec(input.toString(), "Query", "Label", "fixture")), output);
        assertEquals(5, result.records().size());
        assertTrue(result.records().stream().anyMatch(r -> r.rawText.equals("47209")));
        assertTrue(result.records().stream().anyMatch(r -> r.rawText.equals("example@example.com")));
        assertTrue(result.records().stream().anyMatch(r -> r.rawText.equals("ordinary words")));
        RawRecord sql = result.records().stream().filter(r -> r.rawText.equals("SELECT 1"))
                .findFirst().orElseThrow();
        assertEquals(2, sql.occurrences);
        assertEquals(2, sql.origins.size());
        assertEquals("0", result.records().getFirst().rawLabel);
        assertTrue(result.records().stream().anyMatch(r -> r.rawText.equals(" select 1 ")));
        assertDisjoint(result.stratified());
        try (var reader = Files.newBufferedReader(output.resolve("quarantine.csv"));
                var csv = CSVFormat.DEFAULT.builder().setHeader().build().parse(reader)) {
            var rejected = csv.getRecords();
            assertEquals(4, rejected.size());
            assertEquals(2, rejected.stream()
                    .filter(r -> r.get("reason").equals("exact_text_label_conflict")).count());
        }
        try (var reader = Files.newBufferedReader(output.resolve("combined_preprocessed.csv"));
                var csv = CSVFormat.DEFAULT.builder().setHeader().build().parse(reader)) {
            for (var row : csv) {
                assertFalse(row.get("group_id").isBlank());
                assertFalse(row.get("record_id").isBlank());
                assertTrue(Set.of("train", "validation", "test").contains(row.get("split")));
            }
        }
        assertEquals("numeric", InputType.classify("47209"));
        assertEquals("email", InputType.classify("example@example.com"));
        assertEquals("other_text", InputType.classify("ordinary words"));
    }

    @Test
    public void readsBomEncodingsAndPreservesQuotedMultilineText() throws Exception {
        for (var encoding : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE,
                StandardCharsets.UTF_16BE)) {
            Path file = temporary.newFile().toPath();
            Files.writeString(file, "\uFEFFQuery,Label\n\"  hello,\nworld  \",0\n", encoding);
            var rows = CsvUnifier.unifyAll(List.of(
                    new CsvUnifier.SourceSpec(file.toString(), "Query", "Label", "fixture")));
            assertEquals(1, rows.size());
            assertEquals("  hello,\nworld  ", rows.getFirst().rawText);
            assertEquals(1, rows.getFirst().origins.getFirst().row());
        }
    }

    @Test
    public void reportsMissingColumnsAndMalformedRowsInsteadOfGuessing() throws Exception {
        for (String content : List.of("Wrong,Label\nhello,0\n", "Query,Label\nhello,extra,0\n",
                "Query,Query,Label\nhello,world,0\n")) {
            Path file = temporary.newFile().toPath();
            Files.writeString(file, content);
            assertThrows(IOException.class, () -> CsvUnifier.unifyAll(List.of(
                    new CsvUnifier.SourceSpec(file.toString(), "Query", "Label", "fixture"))));
        }
    }

    @Test
    public void balancesUnequalMixedLabelGroupsWithoutOverwritingLabels() {
        List<RawRecord> rows = new ArrayList<>();
        for (int group = 0; group < 50; group++) {
            for (int i = 0; i < 1 + group % 5; i++) {
                rows.add(new RawRecord("select " + group, Integer.toString(i % 2), "fixture"));
            }
        }
        var split = StratifiedSplitter.splitByLabelAndGroup(rows, 0.70, 0.15, 42L);
        assertDisjoint(split);
        assertEquals(rows.size(), split.train().size() + split.val().size() + split.test().size());
        var repeated = StratifiedSplitter.splitByLabelAndGroup(rows, 0.70, 0.15, 42L);
        assertEquals(split, repeated);
        List<List<RawRecord>> partitions = List.of(split.train(), split.val(), split.test());
        double[] ratios = {0.70, 0.15, 0.15};
        for (int label = 0; label < 2; label++) {
            String value = Integer.toString(label);
            long total = rows.stream().filter(r -> r.rawLabel.equals(value)).count();
            for (int i = 0; i < 3; i++) {
                long count = partitions.get(i).stream().filter(r -> r.rawLabel.equals(value)).count();
                assertTrue(Math.abs(count - ratios[i] * total) <= 5);
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> StratifiedSplitter.splitByLabelAndGroup(rows, Double.NaN, 0.15, 42));
        assertThrows(IllegalArgumentException.class,
                () -> StratifiedSplitter.splitByLabelAndGroup(rows, 0.9, 0.2, 42));
        assertTrue(StratifiedSplitter.splitByLabelAndGroup(rows, 1, 0, 42).test().isEmpty());
    }

    @Test
    public void groupsEncodedAndOperatorSpacingVariantsButNeverDeletesThem() {
        var rows = Deduplicator.dedup(List.of(
                new RawRecord("' OR 1=1 --", "1", "a"),
                new RawRecord("' or 1 = 1 --", "1", "b"),
                new RawRecord("%27%20OR%201%3D1%20--", "1", "c")));
        assertEquals(3, rows.size());
        assertEquals(1, rows.stream().map(r -> r.groupId).distinct().count());
        assertDisjoint(StratifiedSplitter.splitByLabelAndGroup(rows, 0.7, 0.15, 42));
        assertEquals("invalid%zz", PayloadNormalizer.groupKey("invalid%zz"));
    }

    @Test
    public void sourceHoldoutExcludesSimilarHeldOutVariantsWithoutTrainingOnHeldOutOnlyRows() {
        RawRecord train = new RawRecord("select 1", "0", "a");
        RawRecord overlap = new RawRecord("SELECT%201", "0", "c");
        RawRecord test = new RawRecord("select 2", "0", "c");
        var split = SourceHoldoutSplitter.holdOutBySource(List.of(train, overlap, test), "c");
        assertEquals(List.of(train), split.get("train_other_sources"));
        assertEquals(List.of(test), split.get("test_holdout_source"));
    }

    @Test
    public void auditsAllThreeRealSourcesWithoutChangingExistingData() throws Exception {
        var raw = CsvUnifier.unifyAll(DatasetPreprocessor.defaultSources());
        Map<String, Set<String>> exactLabels = new HashMap<>();
        for (RawRecord row : raw) {
            String label = row.rawLabel == null ? "" : row.rawLabel.trim();
            if (row.rawText != null && !row.rawText.isBlank() && Set.of("0", "1").contains(label)) {
                exactLabels.computeIfAbsent(row.rawText, key -> new HashSet<>()).add(label);
            }
        }
        Set<String> expected = exactLabels.entrySet().stream().filter(e -> e.getValue().size() == 1)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        Path output = temporary.newFolder("real-audit").toPath();
        var result = DatasetPreprocessor.prepare(DatasetPreprocessor.defaultSources(), output);
        assertEquals(expected, result.records().stream().map(r -> r.rawText).collect(Collectors.toSet()));
        assertTrue(result.records().stream().anyMatch(
                r -> r.source.contains("sqlia") && r.rawLabel.equals("0")));
        assertDisjoint(result.stratified());
        var report = new ObjectMapper().readTree(output.resolve("preprocessing_report.json").toFile());
        int rejected = 0;
        for (var count : report.get("excluded_by_reason")) rejected += count.asInt();
        assertEquals(raw.size(), result.records().size()
                + report.get("merged_exact_duplicates").asInt() + rejected);
        System.out.println("AUDIT " + report);
    }

    private static void assertDisjoint(StratifiedSplitter.Split split) {
        Set<String> seen = new HashSet<>();
        for (var rows : List.of(split.train(), split.val(), split.test())) {
            Set<String> groups = rows.stream().map(r -> r.groupId).collect(Collectors.toSet());
            assertTrue(groups.stream().noneMatch(seen::contains));
            seen.addAll(groups);
        }
    }
}
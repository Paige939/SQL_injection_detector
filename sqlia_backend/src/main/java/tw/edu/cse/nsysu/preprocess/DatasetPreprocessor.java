package tw.edu.cse.nsysu.preprocess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

public final class DatasetPreprocessor {
    public static final String VERSION = "exact-dedup-grouped-v2";
    public static final long SEED = 42L;

    private DatasetPreprocessor() {}

    public record PreparedData(
            List<RawRecord> records,
            StratifiedSplitter.Split stratified,
            Map<String, List<RawRecord>> sourceHoldout) {}

    public record RejectedRecord(RawRecord record, String reason) {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: DatasetPreprocessor <backend-directory> <new-preview-directory>");
        }
        Path backend = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        List<CsvUnifier.SourceSpec> sources = defaultSources().stream()
                .map(spec -> new CsvUnifier.SourceSpec(backend.resolve(spec.path()).toString(),
                        spec.textColumn(), spec.labelColumn(), spec.sourceName()))
                .toList();
        if (Files.exists(output)) {
            throw new IOException("Preview directory already exists; refusing to overwrite: " + output);
        }
        for (CsvUnifier.SourceSpec source : sources) {
            if (!Files.isRegularFile(Path.of(source.path()))) {
                throw new IOException("Source file not found: " + source.path());
            }
        }
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        prepare(sources, output);
    }

    public static List<CsvUnifier.SourceSpec> defaultSources() {
        return List.of(
                new CsvUnifier.SourceSpec("data/raw/Modified_SQL_Dataset.csv",
                        "Query", "Label", "Modified_SQL_Dataset"),
                new CsvUnifier.SourceSpec("data/raw/sqli.csv", "Sentence", "Label", "sqlia"),
                new CsvUnifier.SourceSpec("data/raw/SQLiV3.csv", "Sentence", "Label", "SQLiV3"));
    }

    public static PreparedData prepare() throws IOException {
        return prepare(defaultSources(), Path.of("data/processed"));
    }

    // An explicit output directory allows auditing without importing or training any model.
    public static PreparedData prepare(List<CsvUnifier.SourceSpec> sources, Path output)
            throws IOException {
        List<RawRecord> raw = CsvUnifier.unifyAll(sources);
        List<RejectedRecord> rejected = new ArrayList<>();
        List<RawRecord> valid = LabelValidator.validate(raw);
        Set<RawRecord> validSet = Set.copyOf(valid);
        for (RawRecord row : raw) {
            if (!validSet.contains(row)) rejected.add(new RejectedRecord(row, "invalid_label"));
        }
        List<RawRecord> nonblank = new ArrayList<>();
        for (RawRecord row : valid) {
            if (row.rawText == null || row.rawText.isBlank()) {
                rejected.add(new RejectedRecord(row, "blank_text"));
            } else {
                nonblank.add(row);
            }
        }
        Deduplicator.Result dedup = Deduplicator.partition(nonblank);
        for (RawRecord row : dedup.conflicts()) {
            rejected.add(new RejectedRecord(row, "exact_text_label_conflict"));
        }
        List<RawRecord> records = dedup.records();
        StratifiedSplitter.Split split =
                StratifiedSplitter.splitByLabelAndGroup(records, 0.70, 0.15, SEED);
        Map<String, List<RawRecord>> holdout =
                SourceHoldoutSplitter.holdOutBySource(records, "SQLiV3");
        Files.createDirectories(output);
        Map<String, String> assignments = new LinkedHashMap<>();
        assign(assignments, split.train(), "train");
        assign(assignments, split.val(), "validation");
        assign(assignments, split.test(), "test");
        writeDataset(output.resolve("combined_preprocessed.csv"), records, assignments);
        writeDataset(output.resolve("train.csv"), split.train(), assignments);
        writeDataset(output.resolve("validation.csv"), split.val(), assignments);
        writeDataset(output.resolve("test.csv"), split.test(), assignments);
        writeRejected(output.resolve("quarantine.csv"), rejected);
        writeOrigins(output.resolve("provenance.csv"), records, rejected);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("pipeline_version", VERSION);
        report.put("seed", SEED);
        report.put("target_ratios", Map.of("train", 0.70, "validation", 0.15, "test", 0.15));
        report.put("raw_records", raw.size());
        report.put("raw_by_source", counts(raw, r -> r.source));
        report.put("raw_labels", counts(raw, r -> r.rawLabel == null ? "missing" : r.rawLabel));
        report.put("excluded_by_reason", rejected.stream().collect(Collectors.groupingBy(
                RejectedRecord::reason, LinkedHashMap::new, Collectors.counting())));
        report.put("merged_exact_duplicates", dedup.merged());
        report.put("retained_records", records.size());
        report.put("labels", counts(records, r -> r.rawLabel));
        report.put("input_types", counts(records, r -> r.inputType));
        report.put("source_combinations", counts(records, r -> r.source));
        Map<String, Long> sourceMemberships = new LinkedHashMap<>();
        for (RawRecord row : records) {
            for (String source : row.source.split("\\|")) sourceMemberships.merge(source, 1L, Long::sum);
        }
        report.put("source_memberships", sourceMemberships);
        report.put("groups", records.stream().map(r -> r.groupId).distinct().count());
        report.put("largest_group", counts(records, r -> r.groupId)
                .values().stream().mapToLong(Long::longValue).max().orElse(0));
        Map<String, Object> distributions = new LinkedHashMap<>();
        for (var entry : Map.of("train", split.train(), "validation", split.val(),
                "test", split.test()).entrySet()) {
            distributions.put(entry.getKey(), Map.of("size", entry.getValue().size(),
                    "labels", counts(entry.getValue(), r -> r.rawLabel),
                    "sources", counts(entry.getValue(), r -> r.source),
                    "input_types", counts(entry.getValue(), r -> r.inputType)));
        }
        report.put("splits", distributions);
        report.put("source_holdout_SQLiV3", Map.of(
                "train", holdout.get("train_other_sources").size(),
                "test", holdout.get("test_holdout_source").size(),
                "excluded_overlapping_variants", records.size()
                        - holdout.get("train_other_sources").size()
                        - holdout.get("test_holdout_source").size()));
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(output.resolve("preprocessing_report.json").toFile(), report);
        System.out.println("Preprocessing " + VERSION + ": " + raw.size() + " input, "
                + records.size() + " retained, " + rejected.size() + " quarantined. Output: " + output);
        return new PreparedData(records, split, holdout);
    }

    private static Map<String, Long> counts(List<RawRecord> rows, Function<RawRecord, String> key) {
        return rows.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.counting()));
    }

    private static void assign(Map<String, String> assignments, List<RawRecord> rows, String split) {
        for (RawRecord row : rows) {
            String previous = assignments.putIfAbsent(row.groupId, split);
            if (previous != null && !previous.equals(split)) {
                throw new IllegalStateException("A payload group crossed split boundaries.");
            }
        }
    }

    private static void writeDataset(Path path, List<RawRecord> rows, Map<String, String> assignments)
            throws IOException {
        try (CSVPrinter csv = new CSVPrinter(Files.newBufferedWriter(path, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setHeader("payload", "label", "source", "record_id",
                        "input_type", "normalized_text", "group_id", "split", "occurrences").build())) {
            for (RawRecord row : rows) {
                csv.printRecord(row.rawText, row.rawLabel, row.source, row.recordId, row.inputType,
                        row.normalizedText, row.groupId, assignments.get(row.groupId), row.occurrences);
            }
        }
    }

    private static void writeRejected(Path path, List<RejectedRecord> rejected) throws IOException {
        try (CSVPrinter csv = new CSVPrinter(Files.newBufferedWriter(path, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setHeader("record_id", "payload", "label", "source", "reason")
                        .build())) {
            for (RejectedRecord row : rejected) {
                csv.printRecord(row.record().recordId, row.record().rawText, row.record().rawLabel,
                        row.record().source, row.reason());
            }
        }
    }

    private static void writeOrigins(Path path, List<RawRecord> records, List<RejectedRecord> rejected)
            throws IOException {
        List<RawRecord> all = new ArrayList<>(records);
        rejected.forEach(row -> all.add(row.record()));
        try (CSVPrinter csv = new CSVPrinter(Files.newBufferedWriter(path, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setHeader("record_id", "label", "source", "file", "csv_record").build())) {
            for (RawRecord row : all) {
                for (RawRecord.Origin origin : row.origins) {
                    csv.printRecord(row.recordId, row.rawLabel, origin.source(), origin.file(), origin.row());
                }
            }
        }
    }
}

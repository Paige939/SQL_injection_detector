package tw.edu.cse.nsysu.preprocess;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DatasetPreprocessor {
    private DatasetPreprocessor() {}

    // The result keeps both the import records and the two evaluation views.
    public record PreparedData(
            List<RawRecord> records,
            StratifiedSplitter.Split stratified,
            Map<String, List<RawRecord>> sourceHoldout) {}

    // Run every data-quality step once before rows are written to SQLite.
    public static PreparedData prepare() throws IOException {
        List<CsvUnifier.SourceSpec> sources = List.of(
                new CsvUnifier.SourceSpec(
                        "data/raw/Modified_SQL_Dataset.csv", "Query", "Label", "Modified_SQL_Dataset"),
                new CsvUnifier.SourceSpec(
                        "data/raw/sqli.csv", "Sentence", "Label", "sqlia"),
                new CsvUnifier.SourceSpec(
                        "data/raw/SQLiV3.csv", "Sentence", "Label", "SQLiV3"));

        // Unify UTF-8 and UTF-16 CSV files into one in-memory record format.
        List<RawRecord> records = CsvUnifier.unifyAll(sources);
        records = LabelValidator.validate(records);
        BenignSqlValidator.markBenignValidity(records);

        // Exclude malformed benign rows before deduplication and model training.
        records = records.stream()
                .filter(record -> !"0".equals(record.rawLabel.trim()) || record.benignValid)
                .filter(record -> record.rawText != null && !record.rawText.isBlank())
                .toList();
        records = Deduplicator.dedup(records);
        writeCombinedDataset(records);

        // Keep payload groups together while preserving the label distribution.
        StratifiedSplitter.Split stratified =
                StratifiedSplitter.splitByLabelAndGroup(records, 0.70, 0.15, 42L);
        Map<String, List<RawRecord>> sourceHoldout =
                SourceHoldoutSplitter.holdOutBySource(records, "SQLiV3");

        System.out.println("Preprocessing complete: " + records.size() + " records.");
        System.out.println("Stratified split: train=" + stratified.train().size()
                + ", val=" + stratified.val().size()
                + ", test=" + stratified.test().size());
        System.out.println("Source hold-out SQLiV3: "
                + sourceHoldout.get("test_holdout_source").size() + " records.");
        return new PreparedData(new ArrayList<>(records), stratified, sourceHoldout);
    }

        // Persist the cleaned union so every architecture evaluates the same preprocessed dataset.
        private static void writeCombinedDataset(List<RawRecord> records) throws IOException {
                Path output = Path.of("data/processed/combined_preprocessed.csv");
                Files.createDirectories(output.getParent());
                StringBuilder csv = new StringBuilder("payload,label,source\n");
                for (RawRecord record : records) {
                        csv.append(csvValue(record.rawText)).append(',')
                                        .append(record.rawLabel.trim()).append(',')
                                        .append(csvValue(record.source)).append('\n');
                }
                Files.writeString(output, csv.toString(), StandardCharsets.UTF_8);
                System.out.println("Combined dataset written to " + output);
        }

        private static String csvValue(String value) {
                String safe = value == null ? "" : value;
                return '"' + safe.replace("\"", "\"\"") + '"';
        }
}

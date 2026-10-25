package tw.edu.cse.nsysu.api.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import tw.edu.cse.nsysu.preprocess.CsvUnifier;
import tw.edu.cse.nsysu.preprocess.DatasetPreprocessor;
import tw.edu.cse.nsysu.preprocess.RawRecord;

/** Dedicated, non-destructive entry point for the finite research grid. */
public final class ResearchExperimentRunner {
    private ResearchExperimentRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException(
                    "Usage: ResearchExperimentRunner [backend-directory]");
        }
        Path backend = args.length == 0 ? Path.of(".") : Path.of(args[0]);
        Path normalizedBackend = backend.toAbsolutePath().normalize();
        Path output = normalizedBackend.resolve("data/experiments/research-20261025");
        Path report = run(normalizedBackend, output);
        System.out.println("Research experiments complete. Report: " + report);
    }

    public static Path run(Path backendDirectory, Path outputDirectory) throws Exception {
        Path backend = backendDirectory.toAbsolutePath().normalize();
        List<CsvUnifier.SourceSpec> sourceSpecs = DatasetPreprocessor.defaultSources().stream()
                .map(spec -> new CsvUnifier.SourceSpec(
                        backend.resolve(spec.path()).toString(), spec.textColumn(),
                        spec.labelColumn(), spec.sourceName()))
                .toList();
        for (CsvUnifier.SourceSpec source : sourceSpecs) {
            if (!Files.isRegularFile(Path.of(source.path()))) {
                throw new IOException("Missing source dataset: " + source.path());
            }
        }
        List<RawRecord> rawRecords = CsvUnifier.unifyAll(sourceSpecs);
        List<ResearchExperimentService.Sample> training =
                readMergedSplit(backend.resolve("data/processed/train.csv"), "train");
        List<ResearchExperimentService.Sample> validation =
                readMergedSplit(backend.resolve("data/processed/validation.csv"), "validation");
        Path report = new ResearchExperimentService().run(
                rawRecords, training, validation, outputDirectory);
        Path expected = backend.resolve("data/experiments/research-20261025/research_results.json");
        if (!report.equals(expected.toAbsolutePath().normalize())) {
            System.out.println("Report written outside the default API path: " + report);
        }
        return report;
    }

    private static List<ResearchExperimentService.Sample> readMergedSplit(
            Path path, String expectedSplit) throws IOException {
        List<ResearchExperimentService.Sample> rows = new ArrayList<>();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
                var parser = CSVFormat.DEFAULT.builder()
                        .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).build().parse(reader)) {
            for (String required : List.of("payload", "label", "source", "split", "group_id")) {
                if (!parser.getHeaderMap().containsKey(required)) {
                    throw new IOException("Dataset split lacks required column: " + required + " in " + path);
                }
            }
            for (var row : parser) {
                if (!expectedSplit.equals(row.get("split"))) {
                    throw new IOException("Unexpected split metadata in " + path);
                }
                String label = row.get("label").trim();
                if (!label.equals("0") && !label.equals("1")) {
                    throw new IOException("Non-binary label in " + path);
                }
                String payload = row.get("payload");
                if (payload == null || payload.isBlank()) throw new IOException("Blank payload in " + path);
                rows.add(new ResearchExperimentService.Sample(payload, Integer.parseInt(label),
                        row.get("source"), expectedSplit, row.get("group_id")));
            }
        }
        return List.copyOf(rows);
    }
}

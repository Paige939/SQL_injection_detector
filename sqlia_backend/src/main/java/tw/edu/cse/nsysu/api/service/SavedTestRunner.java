package tw.edu.cse.nsysu.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import tw.edu.cse.nsysu.api.dto.SimulationRequest;

/** Evaluates a trusted saved experiment without training or Validation tuning. */
public final class SavedTestRunner {
    private SavedTestRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: SavedTestRunner <database> <test-csv> <saved-run> <new-report>");
        }
        Path database = Path.of(args[0]).toRealPath();
        Path test = Path.of(args[1]).toRealPath();
        Path run = Path.of(args[2]).toRealPath();
        Path output = Path.of(args[3]).toAbsolutePath().normalize();
        SimulationRequest request = new SimulationRequest();
        request.datasetPath = test.toString();
        request.checkpointDirectory = run.toString();
        request.sampleSize = Integer.MAX_VALUE;
        // Reserve the report before any predictions; never overwrite a previous evaluation.
        try (var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW);
                var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=ro")) {
            ObjectMapper mapper = new ObjectMapper();
            var report = new LinkedHashMap<String, Object>();
            report.put("status", "running");
            report.put("startedAt", java.time.Instant.now().toString());
            try {
                var results = new FrozenArchitectureComparisonService(connection).compare(
                        request, FrozenArchitectureComparisonService.ARCHITECTURES);
                report.put("results", results);
                report.put("testEvaluated", true);
                report.put("retrained", false);
                report.put("thresholdsRetuned", false);
                report.put("completedAt", java.time.Instant.now().toString());
                report.put("status", "complete");
                mapper.writerWithDefaultPrettyPrinter().writeValue(stream, report);
                System.out.println("[test] Saved complete five-architecture evaluation: " + output);
            } catch (Exception failure) {
                report.put("status", "failed");
                report.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
                mapper.writerWithDefaultPrettyPrinter().writeValue(stream, report);
                throw failure;
            }
        }
    }
}

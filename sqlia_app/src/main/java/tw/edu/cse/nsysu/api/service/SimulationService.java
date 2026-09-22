package tw.edu.cse.nsysu.api.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import tw.edu.cse.nsysu.api.dto.SimulationProgress;
import tw.edu.cse.nsysu.api.dto.SimulationRequest;
import tw.edu.cse.nsysu.api.dto.SimulationResult;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

public class SimulationService {
    // Both raw inputs and the cleaned combined dataset live below data/.
    private static final Path ALLOWED_ROOT =
        Paths.get("data").toAbsolutePath().normalize();

    private final DetectionService detectionService;
    private final ModelStateService modelState;
    // A service class that runs simulations to evaluate the performance of a detection model on a given dataset, updating the model with true labels if specified.
    public SimulationService(
            DetectionService detectionService,
            ModelStateService modelState
    ) {
        this.detectionService = detectionService;
        this.modelState = modelState;
    }
    // Runs a simulation on a dataset specified in the [SimulationRequest], returning the simulation results including counts of true positives, false positives, true negatives, false negatives, accuracy, precision, recall, F1 score, and duration.
    public SimulationResult run(SimulationRequest request) throws Exception {
        return run(request, null);
    }
    // Runs a simulation on a dataset specified in the [SimulationRequest], returning the simulation results including counts of true positives, false positives, true negatives, false negatives, accuracy, precision, recall, F1 score, and duration. Optionally accepts a callback to report progress during the simulation.
    public SimulationResult run(
            SimulationRequest request,
            Consumer<SimulationProgress> onProgress
    ) throws Exception {
        Path target = Paths.get(request.datasetPath)
            .toAbsolutePath()
            .normalize();

        if (!target.startsWith(ALLOWED_ROOT)) {
            throw new SecurityException("Dataset path not allowed.");
        }

        long startedAt = System.currentTimeMillis();
        SimulationResult result = new SimulationResult();
    // Open the dataset file, parse it as CSV, shuffle the records, and process each record to evaluate the model's performance and optionally update the model with true labels.
        try (
            Reader reader = openReaderDetectingBom(target);
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(reader)
        ) {
            List<CSVRecord> records = parser.getRecords();
            Collections.shuffle(records, new Random(42));

            int total = Math.min(request.sampleSize, records.size());
            // Process each record in the dataset, predicting the label, updating metrics, and optionally updating the model with the true label.
            for (int index = 0; index < total; index++) {
                CSVRecord record = records.get(index);

                String sql = firstNonNull(
                    record, "Query", "Sentence", "payload", "sql", "query"
                );
                String labelValue = firstNonNull(
                    record, "Label", "label"
                );

                if (sql == null || labelValue == null) {
                    continue;
                }

                int actualLabel = Integer.parseInt(labelValue);
                int predictedLabel = request.architecture == null
                    ? detectionService.detect(sql).label
                    : detectionService.predictArchitecture(sql, request.architecture);

                if (predictedLabel == 1 && actualLabel == 1) {
                    result.tp++;
                } else if (predictedLabel == 1 && actualLabel == 0) {
                    result.fp++;
                } else if (predictedLabel == 0 && actualLabel == 0) {
                    result.tn++;
                } else if (predictedLabel == 0 && actualLabel == 1) {
                    result.fn++;
                } else {
                    throw new IllegalStateException("Unexpected label value.");
                }

                // 先評估未知資料，再用正確標籤更新模型。
                modelState.recordEvaluation(predictedLabel, actualLabel);

                if (request.updateModel) {
                    modelState.update(
                        modelState.toFeature(sql),
                        actualLabel
                    );
                }

                int processed = index + 1;
                if (onProgress != null &&
                    (processed % 10 == 0 || processed == total)) {
                    onProgress.accept(
                        new SimulationProgress(processed, total)
                    );
                }
            }
        }

        result.total = result.tp + result.fp + result.tn + result.fn;
        result.accuracy = result.total == 0
            ? 0
            : (double) (result.tp + result.tn) / result.total;
        result.precision = result.tp + result.fp == 0
            ? 0
            : (double) result.tp / (result.tp + result.fp);
        result.recall = result.tp + result.fn == 0
            ? 0
            : (double) result.tp / (result.tp + result.fn);
        result.f1 = result.precision + result.recall == 0
            ? 0
            : 2 * result.precision * result.recall
                / (result.precision + result.recall);
        result.durationMs = System.currentTimeMillis() - startedAt;

        return result;
    }
    // A helper method to retrieve the first non-null value from a CSV record based on a list of candidate column names.
    private String firstNonNull(CSVRecord record, String... candidates) {
        for (String candidate : candidates) {
            if (record.isMapped(candidate)) {
                return record.get(candidate);
            }
        }
        return null;
    }
    // Opens a file for reading, automatically detecting and handling Byte Order Marks (BOM) for UTF-8, UTF-16LE, and UTF-16BE encodings.
    private Reader openReaderDetectingBom(Path file) throws Exception {
        InputStream input = new BufferedInputStream(
            new FileInputStream(file.toFile())
        );

        input.mark(3);
        byte[] bom = new byte[3];
        int read = input.read(bom);
        Charset charset = StandardCharsets.UTF_8;
        int skip = 0;

        if (read >= 2 && (bom[0] & 0xFF) == 0xFF &&
            (bom[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (read >= 2 && (bom[0] & 0xFF) == 0xFE &&
            (bom[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            skip = 2;
        } else if (read == 3 && (bom[0] & 0xFF) == 0xEF &&
            (bom[1] & 0xFF) == 0xBB &&
            (bom[2] & 0xFF) == 0xBF) {
            skip = 3;
        }

        input.reset();
        input.skip(skip);

        return new InputStreamReader(input, charset);
    }
}
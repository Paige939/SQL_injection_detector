package tw.edu.cse.nsysu.api.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import tw.edu.cse.nsysu.OnlineBagging;
import tw.edu.cse.nsysu.api.dto.SimulationResult;
import tw.edu.cse.nsysu.api.dto.SimulationRequest;
import weka.classifiers.bayes.NaiveBayesUpdateable;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class IncrementalAlgorithmComparisonService {
    private static final Path ALLOWED_ROOT =
        Paths.get("data").toAbsolutePath().normalize();
    private static final long SHUFFLE_SEED = 42L;

    private final ModelStateService modelState;

    public IncrementalAlgorithmComparisonService(ModelStateService modelState) {
        this.modelState = modelState;
    }

    public List<Map<String, Object>> compare(
            SimulationRequest request,
            List<String> architectures
    ) throws Exception {
        if (request.datasetPath == null || request.sampleSize <= 0) {
            throw new IllegalArgumentException("Invalid incremental comparison request.");
        }
        Path dataset = Paths.get(request.datasetPath).toAbsolutePath().normalize();
        if (!dataset.startsWith(ALLOWED_ROOT)) {
            throw new SecurityException("Dataset path not allowed.");
        }

        List<TrainingRow> trainingRows = readTrainingRows();
        Collections.shuffle(trainingRows, new Random(SHUFFLE_SEED));
        List<TestRow> testRows = readTestRows(dataset);
        Collections.shuffle(testRows, new Random(SHUFFLE_SEED));
        if (testRows.size() > request.sampleSize) {
            testRows = new ArrayList<>(testRows.subList(0, request.sampleSize));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (String architecture : architectures) {
            List<float[]> trainingFeatures = modelState.benchmarkFeatures(
                trainingRows.stream().map(row -> row.sql).toList(), architecture);
            List<float[]> testFeatures = modelState.benchmarkFeatures(
                testRows.stream().map(row -> row.sql).toList(), architecture);
            for (IncrementalAlgorithm algorithm : IncrementalAlgorithm.available()) {
                results.add(compareOne(
                    architecture, algorithm, trainingRows, trainingFeatures,
                    testRows, testFeatures, request.updateModel));
            }
        }
        return results;
    }

    private Map<String, Object> compareOne(
            String architecture,
            IncrementalAlgorithm algorithm,
            List<TrainingRow> trainingRows,
            List<float[]> trainingFeatures,
            List<TestRow> testRows,
            List<float[]> testFeatures,
            boolean updateModel
    ) throws Exception {
        Model model = newModel(algorithm, trainingFeatures.get(0).length);
        for (int index = 0; index < trainingRows.size(); index++) {
            model.update(trainingFeatures.get(index), trainingRows.get(index).label);
        }

        int tp = 0;
        int fp = 0;
        int tn = 0;
        int fn = 0;
        long started = System.nanoTime();
        List<Map<String, Object>> accuracyHistory = new ArrayList<>();
        Map<String, SimulationResult.GroupMetrics> bySource = new LinkedHashMap<>();
        Map<String, SimulationResult.GroupMetrics> byInputType = new LinkedHashMap<>();
        for (int index = 0; index < testRows.size(); index++) {
            TestRow row = testRows.get(index);
            float[] features = testFeatures.get(index);
            int prediction = model.predict(features) >= 0.5 ? 1 : 0;
            if (prediction == 1 && row.label == 1) tp++;
            else if (prediction == 1) fp++;
            else if (row.label == 0) tn++;
            else fn++;
            bySource.computeIfAbsent(row.source,
                ignored -> new SimulationResult.GroupMetrics()).add(prediction, row.label);
            byInputType.computeIfAbsent(row.inputType,
                ignored -> new SimulationResult.GroupMetrics()).add(prediction, row.label);
            int processed = index + 1;
            int total = tp + fp + tn + fn;
            if (processed % 25 == 0 || processed == testRows.size()) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("processed", processed);
                point.put("accuracy", (double) (tp + tn) / total);
                accuracyHistory.add(point);
            }
            if (updateModel) model.update(features, row.label);
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        int total = tp + fp + tn + fn;
        double precision = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
        double f1 = precision + recall == 0
            ? 0 : 2 * precision * recall / (precision + recall);
        double fpr = fp + tn == 0 ? 0 : (double) fp / (fp + tn);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("architecture", architecture);
        result.put("algorithm", algorithm.getId());
        result.put("displayName", algorithm.getDisplayName());
        result.put("total", total);
        result.put("tp", tp);
        result.put("fp", fp);
        result.put("tn", tn);
        result.put("fn", fn);
        result.put("accuracy", total == 0 ? 0 : (double) (tp + tn) / total);
        result.put("precision", precision);
        result.put("recall", recall);
        result.put("f1", f1);
        result.put("fpr", fpr);
        result.put("durationMs", durationMs);
        result.put("warmupSamples", trainingRows.size());
        result.put("evaluationMode", updateModel ? "prequential" : "frozen");
        result.put("bySource", bySource);
        result.put("byInputType", byInputType);
        result.put("accuracyHistory", accuracyHistory);
        return result;
    }

    private Model newModel(IncrementalAlgorithm algorithm, int featureCount)
            throws Exception {
        if (algorithm == IncrementalAlgorithm.CURRENT_ONLINE_BAGGING_NB) {
            OnlineBagging bagging = new OnlineBagging(
                new NaiveBayesUpdateable(), 15, 42L);
            bagging.initialize(modelState.benchmarkHeader(featureCount));
            return new Model() {
                @Override
                public double predict(float[] features) throws Exception {
                    return bagging.predictProb(features)[1];
                }

                @Override
                public void update(float[] features, int label) throws Exception {
                    bagging.update(features, label);
                }
            };
        }

        IncrementalClassifier classifier = algorithm == IncrementalAlgorithm.SGD
            ? new SgdIncrementalClassifier()
            : new HoeffdingTreeIncrementalClassifier();
        classifier.initialize(featureCount);
        return new Model() {
            @Override
            public double predict(float[] features) throws Exception {
                return classifier.predictProb(features)[1];
            }

            @Override
            public void update(float[] features, int label) throws Exception {
                classifier.update(features, label);
            }
        };
    }

    private List<TrainingRow> readTrainingRows() throws Exception {
        List<TrainingRow> rows = new ArrayList<>();
        java.util.Set<TrainingKey> seen = new java.util.HashSet<>();
        String sql = "SELECT raw_sql, label FROM DataTable "
            + "WHERE split_group='train' ORDER BY batch_seq, id";
        try (PreparedStatement statement =
                 modelState.benchmarkConnection().prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String rawSql = result.getString("raw_sql");
                int label = result.getInt("label");
                if (seen.add(new TrainingKey(rawSql, label))) {
                    rows.add(new TrainingRow(rawSql, label));
                }
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("No training rows are available for warmup.");
        }
        return rows;
    }

    private List<TestRow> readTestRows(Path dataset) throws Exception {
        List<TestRow> rows = new ArrayList<>();
        try (Reader reader = openReader(dataset);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                 .setHeader()
                 .setSkipHeaderRecord(true)
                 .build()
                 .parse(reader)) {
            for (CSVRecord record : parser) {
                EvaluationDatasetSupport.requireTestSplit(record);
                String sql = EvaluationDatasetSupport.requireSql(record);
                int label = EvaluationDatasetSupport.requireBinaryLabel(record);
                rows.add(new TestRow(sql, label,
                    metricKey(record, "source"), metricKey(record, "input_type", "type")));
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The evaluation dataset contains no labeled rows.");
        }
        EvaluationDatasetSupport.rejectTrainingOverlap(
            modelState.benchmarkConnection(), rows.stream().map(row -> row.sql).toList());
        return rows;
    }

    private Reader openReader(Path file) throws Exception {
        InputStream input = new BufferedInputStream(Files.newInputStream(file));
        input.mark(3);
        byte[] bom = new byte[3];
        int read = input.read(bom);
        Charset charset = StandardCharsets.UTF_8;
        int skip = 0;
        if (read >= 2 && (bom[0] & 0xFF) == 0xFF && (bom[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (read >= 2 && (bom[0] & 0xFF) == 0xFE && (bom[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            skip = 2;
        } else if (read >= 3 && (bom[0] & 0xFF) == 0xEF
                && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF) {
            skip = 3;
        }
        input.reset();
        input.skip(skip);
        return new InputStreamReader(input, charset);
    }

    private String metricKey(CSVRecord record, String... candidates) {
        String value = EvaluationDatasetSupport.firstMapped(record, candidates);
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private interface Model {
        double predict(float[] features) throws Exception;
        void update(float[] features, int label) throws Exception;
    }

    private static final class TrainingRow {
        final String sql;
        final int label;
        TrainingRow(String sql, int label) {
            this.sql = sql;
            this.label = label;
        }
    }

    private static final class TestRow {
        final String sql;
        final int label;
        final String source;
        final String inputType;
        TestRow(String sql, int label, String source, String inputType) {
            this.sql = sql;
            this.label = label;
            this.source = source;
            this.inputType = inputType;
        }
    }

    private record TrainingKey(String sql, int label) {}
}

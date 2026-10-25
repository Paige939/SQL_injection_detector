package tw.edu.cse.nsysu.api.service;

import org.apache.commons.csv.CSVRecord;
import tw.edu.cse.nsysu.preprocess.PayloadNormalizer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class EvaluationDatasetSupport {
    private EvaluationDatasetSupport() {}

    static String firstMapped(CSVRecord record, String... candidates) {
        for (String candidate : candidates) {
            if (record.isMapped(candidate)) return record.get(candidate);
        }
        return null;
    }

    static int requireBinaryLabel(CSVRecord record) {
        String value = firstMapped(record, "Label", "label");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Every evaluation row must have a label.");
        }
        try {
            int label = Integer.parseInt(value.trim());
            if (label == 0 || label == 1) return label;
        } catch (NumberFormatException ignored) {
            // Report malformed and non-binary labels consistently below.
        }
        throw new IllegalArgumentException("Dataset labels must be 0 or 1.");
    }

    static String requireSql(CSVRecord record) {
        String sql = firstMapped(record, "Query", "Sentence", "payload", "sql", "query");
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Every evaluation row must have a SQL payload.");
        }
        return sql;
    }

    static void requireTestSplit(CSVRecord record) {
        for (String field : List.of("split_group", "split")) {
            if (record.isMapped(field)) {
                String split = record.get(field);
                if (split == null || split.isBlank()) {
                    throw new IllegalArgumentException(
                        "Every evaluation row must belong to the test split.");
                }
                if (!split.trim().toLowerCase(Locale.ROOT).equals("test")) {
                    throw new IllegalArgumentException(
                        "Evaluation datasets with a split field must contain only test rows.");
                }
            }
        }
    }

    static void rejectTrainingOverlap(Connection connection, List<String> evaluationSql)
            throws Exception {
        Set<String> trainingGroups = new HashSet<>();
        String sql = "SELECT raw_sql FROM DataTable WHERE split_group IN ('train','validation')";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                trainingGroups.add(PayloadNormalizer.groupKey(rows.getString(1)));
            }
        }
        for (String payload : evaluationSql) {
            if (trainingGroups.contains(PayloadNormalizer.groupKey(payload))) {
                throw new IllegalArgumentException(
                    "Evaluation dataset overlaps a training or validation payload group.");
            }
        }
    }
}

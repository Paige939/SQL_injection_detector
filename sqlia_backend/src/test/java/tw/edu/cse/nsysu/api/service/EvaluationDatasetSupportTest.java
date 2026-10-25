package tw.edu.cse.nsysu.api.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.Test;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class EvaluationDatasetSupportTest {
    @Test
    public void acceptsOnlyTestSplitAndBinaryLabels() throws Exception {
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).build()
                .parse(new StringReader("payload,label,split_group\nselect 1,0,test\n"))) {
            CSVRecord row = parser.getRecords().get(0);
            EvaluationDatasetSupport.requireTestSplit(row);
            assertEquals("select 1", EvaluationDatasetSupport.requireSql(row));
            assertEquals(0, EvaluationDatasetSupport.requireBinaryLabel(row));
        }
    }

    @Test
    public void rejectsNonTestRowsAndInvalidLabels() throws Exception {
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).build()
                .parse(new StringReader("payload,label,split_group\nselect 1,2,train\n"))) {
            CSVRecord row = parser.getRecords().get(0);
            expectIllegalArgument(() -> EvaluationDatasetSupport.requireTestSplit(row));
            expectIllegalArgument(() -> EvaluationDatasetSupport.requireBinaryLabel(row));
        }
    }

    @Test
    public void rejectsGroupOverlapWithAnyDatabaseTrainingRow() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE DataTable(raw_sql TEXT, split_group TEXT)");
            statement.execute("INSERT INTO DataTable VALUES "
                + "('SELECT  *  FROM users;', 'train')");
            EvaluationDatasetSupport.rejectTrainingOverlap(
                connection, List.of("select%20*%20from%20users"));
            fail("Expected normalized group overlap rejection.");
        } catch (IllegalArgumentException expected) {
            // Expected: URL/spacing and operator variants share a group key.
        }
    }

    @Test
    public void permitsCustomPayloadsWithoutTrainingGroupOverlap() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE DataTable(raw_sql TEXT, split_group TEXT)");
            statement.execute("INSERT INTO DataTable VALUES ('select 1', 'train')");
            EvaluationDatasetSupport.rejectTrainingOverlap(
                connection, List.of("delete from audit_log"));
        }
    }

    @Test
    public void rejectsValidationGroupOverlapAsWellAsTraining() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE DataTable(raw_sql TEXT, split_group TEXT)");
            statement.execute("INSERT INTO DataTable VALUES ('select 1', 'validation')");
            expectIllegalArgument(() -> EvaluationDatasetSupport.rejectTrainingOverlap(
                    connection, List.of("SELECT%201")));
        }
    }

    private static void expectIllegalArgument(ThrowingRunnable action) throws Exception {
        try {
            action.run();
            fail("Expected IllegalArgumentException.");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

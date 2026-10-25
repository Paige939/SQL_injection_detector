package tw.edu.cse.nsysu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.Test;

public class DataImporterMigrationTest {
    @Test
    public void renamesLegacyTableAndPreservesItsRows() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE training_data (id INTEGER PRIMARY KEY, payload TEXT)");
            statement.execute("INSERT INTO training_data VALUES (1, 'SELECT 1')");

            DataImporter.migrateLegacyTable(connection);

            try (var result = statement.executeQuery("SELECT payload FROM DataTable WHERE id=1")) {
                assertTrue(result.next());
                assertEquals("SELECT 1", result.getString("payload"));
            }
            try (var result = statement.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='training_data'")) {
                assertFalse(result.next());
            }
        }
    }

    @Test
    public void rejectsAmbiguousLegacyAndCurrentTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE training_data (id INTEGER)");
            statement.execute("CREATE TABLE DataTable (id INTEGER)");

            try {
                DataImporter.migrateLegacyTable(connection);
                fail("Expected duplicate table names to be rejected.");
            } catch (SQLException error) {
                assertEquals(
                    "Both training_data and DataTable tables exist; resolve the duplicate tables before importing.",
                    error.getMessage());
            }
        }
    }
}

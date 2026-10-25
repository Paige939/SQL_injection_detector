package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FeedbackJournalTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void defaultIsLocalProjectScopedAndWritableAcrossReopens() throws Exception {
        Path backend = temporary.newFolder("external project").toPath();
        Path home = temporary.newFolder("home").toPath();
        Path target = FeedbackJournal.location(backend, null, home);
        assertTrue(target.startsWith(home));
        assertFalse(target.startsWith(backend));
        assertEquals(target, FeedbackJournal.location(backend.resolve("."), null, home));
        assertNotEquals(target, FeedbackJournal.location(temporary.newFolder("other").toPath(), null, home));
        Files.createDirectories(target.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + target);
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE feedback(value TEXT)");
            statement.executeUpdate("INSERT INTO feedback VALUES('preserved')");
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + target);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT value FROM feedback")) {
            assertTrue(rows.next());
            assertEquals("preserved", rows.getString(1));
        }
        Path explicit = temporary.getRoot().toPath().resolve("explicit.db");
        assertEquals(explicit, FeedbackJournal.location(backend, explicit.toString(), home));
        assertThrows(IllegalArgumentException.class, () -> FeedbackJournal.location(backend, "", home));
    }

    @Test
    public void doesNotSilentlyDiscardLegacyFeedback() throws Exception {
        Path backend = temporary.newFolder("backend").toPath();
        Path home = temporary.newFolder("home").toPath();
        Path legacy = backend.resolve("data/runtime/feedback.db");
        Files.createDirectories(legacy.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + legacy);
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE deployment(checkpoint TEXT)");
            // An interrupted initial startup with no feedback is safe to leave untouched.
            assertTrue(FeedbackJournal.location(backend, null, home).startsWith(home));
            statement.execute("CREATE TABLE feedback(value TEXT)");
            assertTrue(FeedbackJournal.location(backend, null, home).startsWith(home));
            statement.executeUpdate("INSERT INTO feedback VALUES('must keep')");
            assertThrows(IllegalStateException.class, () -> FeedbackJournal.location(backend, null, home));
            assertEquals(legacy, FeedbackJournal.location(backend, legacy.toString(), home));
        }
    }
}

package tw.edu.cse.nsysu.api.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;

public final class FeedbackJournal {
    private FeedbackJournal() {}

    public static Path location(Path backend, String override) throws Exception {
        return location(backend, override, Path.of(System.getProperty("user.home")));
    }

    static Path location(Path backend, String override, Path home) throws Exception {
        if (override != null) {
            if (override.isBlank()) throw new IllegalArgumentException("sqlia.feedback must not be blank.");
            return Path.of(override).toAbsolutePath().normalize();
        }
        Path project = backend.toRealPath();
        String projectId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(project.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        Path target = home.resolve(".sqlia/runtime").resolve(projectId).resolve("feedback.db");
        Path legacy = project.resolve("data/runtime/feedback.db");
        if (!Files.exists(target) && Files.isRegularFile(legacy)) {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + legacy.toUri() + "?mode=ro");
                    var query = connection.createStatement();
                    var tables = query.executeQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name='feedback'")) {
                if (tables.next()) {
                    try (var count = connection.createStatement();
                            var rows = count.executeQuery("SELECT COUNT(*) FROM feedback")) {
                        if (rows.next() && rows.getLong(1) > 0) {
                            throw new IllegalStateException("Existing feedback must be preserved. With the API stopped, "
                                    + "back up and migrate " + legacy + " to " + target
                                    + " before using the new default, or explicitly set -Dsqlia.feedback.");
                        }
                    }
                }
            }
        }
        return target;
    }
}

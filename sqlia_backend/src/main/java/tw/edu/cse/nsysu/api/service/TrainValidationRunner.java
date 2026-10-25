package tw.edu.cse.nsysu.api.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

public final class TrainValidationRunner {
    private TrainValidationRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: TrainValidationRunner <existing-database> <new-output-directory>");
        }
        Path database = Path.of(args[0]).toRealPath();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(database)) throw new IllegalArgumentException("Database not found.");
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toUri() + "?mode=ro")) {
            new FrozenArchitectureComparisonService(connection).trainAndValidate(output);
        }
    }
}

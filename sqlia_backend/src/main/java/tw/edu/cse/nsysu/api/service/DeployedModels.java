package tw.edu.cse.nsysu.api.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;

/** Frozen serving model and an independently loaded, journaled interactive copy. */
public final class DeployedModels implements AutoCloseable {
    private final ModelStateService frozen;
    private final ModelStateService interactive;
    private final Connection journal;
    private final String checkpoint;
    private final String directory;
    private final Connection data;
    private final Path allowedRoot;
    private final Path journalPath;
    private boolean healthy = true;
    private int feedbackCount;

    public DeployedModels(Connection data, Path run, Path feedbackDatabase) throws Exception {
        this(data, run, feedbackDatabase, Path.of("data"));
    }

    DeployedModels(Connection data, Path run, Path feedbackDatabase, Path allowedRoot) throws Exception {
        this.data = data;
        this.allowedRoot = allowedRoot;
        this.journalPath = feedbackDatabase.toAbsolutePath().normalize();
        var loader = new FrozenArchitectureComparisonService(data, allowedRoot,
                training -> { throw new IllegalStateException("Deployment must not train."); });
        var saved = loader.loadSaved(run.toString());
        directory = run.toRealPath().toString();
        checkpoint = saved.checkpointHash();
        Map<String, Double> thresholds = new LinkedHashMap<>();
        saved.thresholds().forEach((key, value) -> thresholds.put(key, value.threshold()));
        frozen = saved.state();
        frozen.configureDeployment(thresholds, checkpoint, true);
        // A second verified deserialization ensures no shared mutable object graph.
        interactive = loader.loadSaved(directory).state();
        interactive.configureDeployment(thresholds, checkpoint, false);
        interactive.initializeInteractive(data);
        Files.createDirectories(feedbackDatabase.toAbsolutePath().getParent());
        journal = DriverManager.getConnection("jdbc:sqlite:" + journalPath);
        try (var query = journal.createStatement()) {
            query.execute("CREATE TABLE IF NOT EXISTS deployment(checkpoint TEXT NOT NULL)");
            query.execute("CREATE TABLE IF NOT EXISTS feedback(id INTEGER PRIMARY KEY,sql TEXT NOT NULL,"
                    + "label INTEGER NOT NULL,benign INTEGER NOT NULL,model TEXT NOT NULL,created TEXT NOT NULL)");
            try (var rows = query.executeQuery("SELECT checkpoint FROM deployment")) {
                if (rows.next() && !checkpoint.equals(rows.getString(1))) {
                    throw new IllegalStateException("Feedback journal belongs to a different deployed checkpoint.");
                }
            }
            query.executeUpdate("INSERT INTO deployment SELECT '" + checkpoint
                    + "' WHERE NOT EXISTS(SELECT 1 FROM deployment)");
            try (var rows = query.executeQuery("SELECT sql,label,benign,model FROM feedback ORDER BY id")) {
                while (rows.next()) {
                    interactive.applyIncrementalFeedback(rows.getString(1), rows.getInt(2),
                            rows.getBoolean(3), rows.getString(4));
                    feedbackCount++;
                }
            }
        } catch (Exception failure) {
            try {
                journal.close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw new IllegalStateException("Cannot initialize persistent feedback journal at " + journalPath
                    + ". Check permissions, filesystem support (avoid FAT/MS-DOS), and checkpoint identity. "
                    + "Select a local journal via -Dsqlia.feedback; no fallback was created.", failure);
        }
    }

    public ModelStateService frozen() { return frozen; }
    public ModelStateService interactive() { return interactive; }

    public ModelStateService newReplaySession() throws Exception {
        var saved = new FrozenArchitectureComparisonService(data, allowedRoot,
                training -> { throw new IllegalStateException("Replay must not retrain the checkpoint."); })
                .loadSaved(directory);
        var state = saved.state();
        Map<String, Double> thresholds = new LinkedHashMap<>();
        saved.thresholds().forEach((key, value) -> thresholds.put(key, value.threshold()));
        state.configureDeployment(thresholds, checkpoint, false);
        state.initializeInteractive(data);
        return state;
    }

    public synchronized void feedback(String sql, int label, boolean benign, String model) throws Exception {
        if (!healthy) throw new IllegalStateException("Feedback application failed; restart and inspect the journal.");
        SingleInputValidation.requireValid(sql);
        if (label != 0 && label != 1) throw new IllegalArgumentException("Verified label must be 0 or 1.");
        if (benign && label != 0) throw new IllegalArgumentException("Verified benign requires label 0.");
        String selected = IncrementalAlgorithm.fromId(model).getId();
        // Write-ahead journal: restart replays every acknowledged or interrupted update exactly once.
        try (var insert = journal.prepareStatement(
                "INSERT INTO feedback(sql,label,benign,model,created) VALUES(?,?,?,?,?)")) {
            insert.setString(1, sql);
            insert.setInt(2, label);
            insert.setBoolean(3, benign);
            insert.setString(4, selected);
            insert.setString(5, java.time.Instant.now().toString());
            insert.executeUpdate();
        }
        try {
            interactive.applyIncrementalFeedback(sql, label, benign, selected);
            feedbackCount++;
        } catch (Exception failure) {
            healthy = false;
            throw new IllegalStateException("Journaled feedback failed to apply; restart required.", failure);
        }
    }

    public synchronized Map<String, Object> status() {
        return Map.ofEntries(Map.entry("deploymentMode", "frozen_with_isolated_interactive_copy"),
                Map.entry("feedbackJournal", journalPath.toString()),
                Map.entry("checkpointDirectory", directory), Map.entry("checkpointSha256", checkpoint),
                Map.entry("trainingSamples", frozen.getTotalTrained()),
                Map.entry("thresholds", frozen.getArchitectureThresholds()),
                Map.entry("feedbackCount", feedbackCount), Map.entry("feedbackHealthy", healthy),
                Map.entry("feedbackPersistence", "write_ahead_sqlite_journal"),
                Map.entry("interactiveModels", interactive.getIncrementalModels()));
    }

    @Override public void close() throws Exception { journal.close(); }
}

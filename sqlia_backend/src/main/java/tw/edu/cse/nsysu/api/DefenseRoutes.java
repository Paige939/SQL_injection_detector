package tw.edu.cse.nsysu.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import tw.edu.cse.nsysu.api.service.DefenseEvaluation;
import tw.edu.cse.nsysu.api.service.DefenseModels;
import tw.edu.cse.nsysu.api.service.DefenseService;
import tw.edu.cse.nsysu.api.service.DefenseStore;
import tw.edu.cse.nsysu.api.service.FrozenArchitectureComparisonService;
import tw.edu.cse.nsysu.api.service.IncrementalAlgorithm;
import tw.edu.cse.nsysu.api.service.ModelStateService;

public final class DefenseRoutes {
    private DefenseRoutes() {}

    public static void register(Javalin app, ModelStateService website, Connection training) throws Exception {
        DefenseModels models = new DefenseModels(website, training);
        // Keep writable SQLite state on a local filesystem; the project may live on exFAT.
        DefenseStore store = new DefenseStore(Path.of(System.getProperty("sqlia.defense.database",
                Path.of(System.getProperty("user.home"), ".sqlia", "defense-lab.db").toString())));
        register(app, models, models.trainingSamples(), training, Path.of("data/processed/test.csv"), store);
    }

    public static void register(Javalin app, DefenseService.Predictor models, int trainingSamples,
            Connection training, Path dataset, DefenseStore store) {
        DefenseService service = new DefenseService(models, store, store);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        DefenseEvaluation evaluation = new DefenseEvaluation(models, training, dataset, store);
        app.events(events -> events.serverStopped(store::close));
        app.get("/api/defense/status", ctx -> ctx.json(Map.of(
                "mode", "ENFORCE", "reviewPolicy", "user_decision",
                "modelVersion", models.version(), "trainingSamples", trainingSamples,
                "architectures", FrozenArchitectureComparisonService.ARCHITECTURES,
                "algorithms", IncrementalAlgorithm.available().stream().map(algorithm -> Map.of(
                        "id", algorithm.getId(), "displayName", algorithm.getDisplayName())).toList(),
                "note", "Review waits for an explicit user decision; allowed searches use parameterized queries. "
                        + "Fixed startup snapshot; no feedback or replay updates. Local controlled demo only.")));
        app.post("/api/defense/search", ctx -> {
            try {
                var result = service.search(mapper.readValue(ctx.body(), DefenseService.Request.class));
                ctx.status("rejected".equals(result.status) ? 403 : 200).json(result);
            } catch (DefenseService.Failure error) {
                error.printStackTrace();
                ctx.status(error.httpStatus).json(error.result);
            } catch (IllegalArgumentException | JsonProcessingException error) {
                ctx.status(400).json(Map.of("error", "INVALID_SEARCH_REQUEST", "message", error.getMessage()));
            } catch (Exception error) {
                failure(ctx, error);
            }
        });
        app.post("/api/defense/review", ctx -> {
            try {
                var result = service.resolveReview(mapper.readValue(ctx.body(), DefenseService.ReviewRequest.class));
                ctx.status("rejected".equals(result.status) ? 403 : 200).json(result);
            } catch (DefenseService.ReviewResolutionFailure error) {
                ctx.status(error.httpStatus).json(Map.of(
                        "errorCode", error.errorCode, "message", error.getMessage()));
            } catch (DefenseService.Failure error) {
                error.printStackTrace();
                ctx.status(error.httpStatus).json(error.result);
            } catch (IllegalArgumentException | JsonProcessingException error) {
                ctx.status(400).json(Map.of("error", "INVALID_REVIEW_REQUEST", "message", error.getMessage()));
            } catch (Exception error) {
                failure(ctx, error);
            }
        });
        app.get("/api/defense/audit", ctx -> {
            try {
                var rows = store.recent(200);
                ctx.json(Map.of("records", rows, "summary", DefenseEvaluation.auditSummary(rows)));
            } catch (Exception error) { failure(ctx, error); }
        });
        app.get("/api/defense/evaluation", ctx -> {
            try { ctx.json(store.latestReport()); }
            catch (Exception error) { failure(ctx, error); }
        });
        app.post("/api/defense/evaluation", ctx -> {
            try { ctx.json(evaluation.run(mapper.readValue(ctx.body(), DefenseEvaluation.Request.class))); }
            catch (IllegalArgumentException | JsonProcessingException error) {
                ctx.status(400).json(Map.of("error", "INVALID_EVALUATION_REQUEST", "message", error.getMessage()));
            } catch (java.nio.file.NoSuchFileException error) {
                ctx.status(409).json(Map.of("error", "TEST_DATASET_MISSING",
                        "message", "Prepare data/processed/test.csv before evaluating."));
            } catch (Exception error) { failure(ctx, error); }
        });
    }

    private static void failure(Context ctx, Exception error) {
        error.printStackTrace();
        ctx.status(500).json(Map.of("error", "DEFENSE_OPERATION_FAILED",
                "message", "Defense operation failed; no successful outcome is assumed. Check the server log and audit."));
    }
}

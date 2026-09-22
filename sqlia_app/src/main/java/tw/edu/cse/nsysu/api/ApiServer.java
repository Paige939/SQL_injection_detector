package tw.edu.cse.nsysu.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import tw.edu.cse.nsysu.api.dto.SimulationRequest;
import tw.edu.cse.nsysu.api.dto.DetectRequest;
import tw.edu.cse.nsysu.api.service.DetectionService;
import tw.edu.cse.nsysu.api.service.ModelStateService;
import tw.edu.cse.nsysu.api.service.SimulationService;
import tw.edu.cse.nsysu.api.service.RuleMiningService;
import tw.edu.cse.nsysu.api.dto.IncrementalFeedRequest;
import tw.edu.cse.nsysu.api.dto.VerifiedFeedbackRequest;
import tw.edu.cse.nsysu.api.dto.SimulationJob;
import tw.edu.cse.nsysu.api.dto.SimulationResult;
import tw.edu.cse.nsysu.api.service.IncrementalService;
import tw.edu.cse.nsysu.api.service.SimulationJobService;
import java.sql.Connection;
import java.sql.DriverManager;

public class ApiServer{
    public static void main(String[] args) throws Exception{
        Connection conn = DriverManager.getConnection("jdbc:sqlite:db/SQLIA.db");
        ModelStateService modelState = new ModelStateService(conn, 2000, 15);
        DetectionService detectionService = new DetectionService(modelState);
        SimulationService simulationService = new SimulationService(detectionService, modelState);
        RuleMiningService ruleMiningService = new RuleMiningService();
        ObjectMapper mapper = new ObjectMapper();
        SimulationJobService simulationJobService = new SimulationJobService(simulationService);
        IncrementalService incrementalService = new IncrementalService(detectionService, modelState);
        //Create a Javalin app with CORS enabled and define API endpoints for detection, simulation, and rule mining
        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.reflectClientOrigin = true));
        }).start(8080);
        //Define an endpoint to detect SQL injection for a given SQL query, returning the label, confidence, and extracted features
        app.post("/api/detect", ctx -> {
            DetectRequest req = mapper.readValue(ctx.body(), DetectRequest.class);
            if(req.sql == null || req.sql.length() > 4000){
                ctx.status(400).json("{\"error\":\"Invalid SQL length\"}");
                return;
            }
            ctx.json(detectionService.detect(req.sql));
        });
        // Return benign/malicious prediction and confidence for all three architectures.
        app.post("/api/architectures/predict", ctx -> {
            DetectRequest req = mapper.readValue(ctx.body(), DetectRequest.class);
            if (req.sql == null || req.sql.isBlank() || req.sql.length() > 4000) {
                ctx.status(400).json(java.util.Map.of("error", "Invalid SQL or request length."));
                return;
            }
            ctx.json(detectionService.predictAllArchitectures(req.sql));
        });
        //Define an endpoint to get the model's warmup status and total number of trained instances
        app.get("/api/incremental/status", ctx -> {
            ctx.json(new Object(){
                public final boolean isWarmUp = modelState.isWarmedUp();
                public final int totalTrained = modelState.getTotalTrained();
                public final int normalProfileSize = modelState.getNormalProfileSize();
                public final java.util.Map<Integer, Integer> warmupLabelCounts = modelState.getWarmupLabelCounts();
            });
        });
        //Define an endpoint to get the model's accuracy history, returning a list of AccuracyPoint objects with index, accuracy, total evaluated, and timestamp
        app.get("/api/stats/accuracy", ctx -> {
            ctx.json(modelState.getAccuracyHistory());
        });
        //Define an endpoint to start a new simulation job, validating the request and returning the created SimulationJob object with job ID and initial status
        app.post("/api/simulation/jobs", ctx -> {
            SimulationRequest request = mapper.readValue(
            ctx.body(),
            SimulationRequest.class
            );

            if (request.datasetPath == null || request.sampleSize <= 0) {
            ctx.status(400).json(
                java.util.Map.of("error", "Invalid simulation request.")
            );
                return;
            }

            ctx.status(202).json(simulationJobService.start(request));
        });
        // Run the same held-out data through all three architecture paths.
        app.post("/api/performance/compare", ctx -> {
            try {
                SimulationRequest request = mapper.readValue(ctx.body(), SimulationRequest.class);
                if (request.datasetPath == null || request.sampleSize <= 0) {
                    ctx.status(400).json(java.util.Map.of("error", "Invalid performance request."));
                    return;
                }
                java.util.List<java.util.Map<String, Object>> results = new java.util.ArrayList<>();
                for (String architecture : java.util.List.of(
                        "ARM_PROFILE", "RATIO_INCREMENTAL_ML", "FULL_FLOW")) {
                    SimulationRequest runRequest = new SimulationRequest();
                    runRequest.datasetPath = request.datasetPath;
                    runRequest.sampleSize = request.sampleSize;
                    runRequest.updateModel = false;
                    runRequest.architecture = architecture;
                    SimulationResult result = simulationService.run(runRequest);
                    results.add(java.util.Map.of(
                            "architecture", architecture,
                            "total", result.total,
                            "accuracy", result.accuracy,
                            "precision", result.precision,
                            "recall", result.recall,
                            "f1", result.f1,
                            "durationMs", result.durationMs));
                }
                ctx.json(results);
            } catch (SecurityException | java.nio.file.NoSuchFileException error) {
                ctx.status(400).json(java.util.Map.of("error", error.getMessage()));
            } catch (Exception error) {
                error.printStackTrace();
                ctx.status(500).json(java.util.Map.of(
                        "error", error.getClass().getSimpleName() + ": " + error.getMessage()));
            }
        });
        //Define an endpoint to retrieve the status of a simulation job by its job ID, returning the SimulationJob object or a 404 error if the job is not found
        app.get("/api/simulation/jobs/{jobId}", ctx -> {
            SimulationJob job = simulationJobService.get(
                ctx.pathParam("jobId")
            );

            if (job == null) {
                ctx.status(404).json(
                    java.util.Map.of("error", "Simulation job not found.")
                );
                return;
            }

            ctx.json(job);
        });
        //Define an endpoint to process incremental feed requests, validating the input, detecting the label and confidence of the SQL query, optionally updating the model with the true label, and returning a response containing the predicted label, confidence score, whether the model was updated, total number of samples trained, and the features used for prediction
        app.post("/api/incremental/feed", ctx -> {
            IncrementalFeedRequest request = mapper.readValue(
                ctx.body(),
                IncrementalFeedRequest.class
            );

            try {
                ctx.json(incrementalService.process(request));
            } catch (IllegalArgumentException error) {
                ctx.status(400).json(
                    java.util.Map.of("error", error.getMessage())
                );
            }
        });
        // Apply trusted feedback: every verified label updates ML, but only verified benign updates ARM.
        app.post("/api/feedback/verified", ctx -> {
            VerifiedFeedbackRequest request = mapper.readValue(
                ctx.body(), VerifiedFeedbackRequest.class);
            try {
            modelState.applyVerifiedFeedback(
                request.sql, request.verifiedLabel, request.verifiedBenign);
            ctx.json(java.util.Map.of(
                "updatedMl", true,
                "updatedArm", request.verifiedBenign && request.verifiedLabel == 0,
                "normalProfileSize", modelState.getNormalProfileSize()));
            } catch (IllegalArgumentException error) {
            ctx.status(400).json(java.util.Map.of("error", error.getMessage()));
            }
        });
        //Define a mapping of rule set IDs to their corresponding rule file paths for the rule mining service
        java.util.Map<String, String> ruleFiles = java.util.Map.of(
            "stage4-v1-fpgrowth-01-06",
            "data/processed/Stage4/Version1/FPGrowth_output_minSup_0.1_minConf_0.6.txt",

            "stage4-v1-fpgrowth-01-08",
            "data/processed/Stage4/Version1/FPGrowth_output_minSup_0.1_minConf_0.8.txt",

            "stage4-v1-fpgrowth-005-06",
            "data/processed/Stage4/Version1/FPGrowth_output_minSup_0.05_minConf_0.6.txt",

            "stage4-v1-fpgrowth-005-08",
            "data/processed/Stage4/Version1/FPGrowth_output_minSup_0.05_minConf_0.8.txt",

            "stage4-v1-eclat-01",
            "data/processed/Stage4/Version1/Eclat_output_minSup_0.1.txt",

            "stage4-v1-eclat-005",
            "data/processed/Stage4/Version1/Eclat_output_minSup_0.05.txt"
        );
        //Eclat itemset files have no antecedent/consequent split, so they need the itemset parser instead of the rule parser
        java.util.Set<String> itemsetRuleSetIds = java.util.Set.of(
            "stage4-v1-eclat-01",
            "stage4-v1-eclat-005"
        );
        //Define an endpoint to retrieve the rules for a given rule set ID, returning the parsed rules from the corresponding rule file
        app.get("/api/rules", ctx -> {
            String ruleSetId = ctx.queryParam("ruleSetId");
            if(ruleSetId == null || !ruleFiles.containsKey(ruleSetId)){
                ctx.status(400).json(java.util.Map.of(
                    "error", "Unknown ruleSetId",
                    "availableResultSetIds", ruleFiles.keySet()
                ));
                return;
            }
            String filePath = ruleFiles.get(ruleSetId);
            if(itemsetRuleSetIds.contains(ruleSetId)){
                ctx.json(ruleMiningService.parseItemsetFile(filePath));
            } else {
                ctx.json(ruleMiningService.parseRuleFile(filePath));
            }
        });

        System.out.println("API server started at http://localhost:8080");
        // Add a shutdown hook to gracefully shut down the simulation job service when the application is terminated
        Runtime.getRuntime().addShutdownHook(
            new Thread(simulationJobService::shutdown)
        );
    }
}
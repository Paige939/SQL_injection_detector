package tw.edu.cse.nsysu.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import tw.edu.cse.nsysu.api.dto.SimulationRequest;
import tw.edu.cse.nsysu.api.dto.DetectRequest;
import tw.edu.cse.nsysu.api.service.DetectionService;
import tw.edu.cse.nsysu.api.service.SingleInputValidation;
import tw.edu.cse.nsysu.api.service.FrozenArchitectureComparisonService;
import tw.edu.cse.nsysu.api.service.ModelStateService;
import tw.edu.cse.nsysu.api.service.SimulationService;
import tw.edu.cse.nsysu.api.service.RuleMiningService;
import tw.edu.cse.nsysu.api.dto.IncrementalFeedRequest;
import tw.edu.cse.nsysu.api.dto.VerifiedFeedbackRequest;
import tw.edu.cse.nsysu.api.dto.SimulationJob;
import tw.edu.cse.nsysu.api.dto.IncrementalStatusResponse;
import tw.edu.cse.nsysu.api.service.IncrementalService;
import tw.edu.cse.nsysu.api.service.SimulationJobService;
import tw.edu.cse.nsysu.api.service.IncrementalAlgorithmComparisonService;
import tw.edu.cse.nsysu.api.service.WebsiteComparisonService;
import tw.edu.cse.nsysu.api.service.ExternalReportService;
import tw.edu.cse.nsysu.api.service.RuleAblationReportService;
import java.sql.Connection;
import java.sql.DriverManager;

public class ApiServer{
    public static void main(String[] args) throws Exception{
        java.nio.file.Path database = java.nio.file.Path.of("db/SQLIA.db").toRealPath();
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=ro");
        ModelStateService modelState = new ModelStateService(conn, 2000, 15);
        modelState.calibrateWebsitePureArmThreshold();
        DetectionService detectionService = new DetectionService(modelState);
        SimulationService simulationService = new SimulationService(detectionService, modelState);
        RuleMiningService ruleMiningService = new RuleMiningService();
        ObjectMapper mapper = new ObjectMapper();
        SimulationJobService simulationJobService = new SimulationJobService(simulationService);
        IncrementalService incrementalService = new IncrementalService(detectionService, modelState);
        IncrementalAlgorithmComparisonService incrementalComparisonService =
            new IncrementalAlgorithmComparisonService(modelState);
        WebsiteComparisonService websiteComparison =
            new WebsiteComparisonService(simulationService, modelState);
        //Create a Javalin app with CORS enabled and define API endpoints for detection, simulation, and rule mining
        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.reflectClientOrigin = true));
        }).start(Integer.getInteger("sqlia.port", 8080));
        registerExternalReportRoutes(app, new ExternalReportService(
                java.nio.file.Path.of("data/experiments")));
        registerRuleAblationReportRoutes(app, new RuleAblationReportService(
                java.nio.file.Path.of("data/experiments")));
        //Define an endpoint to detect SQL injection for a given SQL query, returning the label, confidence, and extracted features
        app.post("/api/detect", ctx -> {
            DetectRequest req = mapper.readValue(ctx.body(), DetectRequest.class);
            if (!SingleInputValidation.isValid(req.sql)) {
                ctx.status(400).json(java.util.Map.of("error",
                        SingleInputValidation.MESSAGE));
                return;
            }
            try {
                ctx.json(detectionService.detect(req.sql, req.modelId));
            } catch (IllegalArgumentException error) {
                ctx.status(400).json(java.util.Map.of("error", error.getMessage()));
            }
        });
        // Compare all five architecture paths.
        app.post("/api/architectures/predict", ctx -> {
            DetectRequest req = mapper.readValue(ctx.body(), DetectRequest.class);
            if (!SingleInputValidation.isValid(req.sql)) {
                ctx.status(400).json(java.util.Map.of("error",
                        SingleInputValidation.MESSAGE));
                return;
            }
            ctx.json(detectionService.predictAllArchitectures(req.sql));
        });
        // Compare only the two Extended ARM methods.
        app.post("/api/extended/architectures/predict", ctx -> {
            DetectRequest req = mapper.readValue(ctx.body(), DetectRequest.class);
            if (!SingleInputValidation.isValid(req.sql)) {
                ctx.status(400).json(java.util.Map.of("error",
                        SingleInputValidation.MESSAGE));
                return;
            }
            ctx.json(detectionService.predictExtendedArchitectures(req.sql));
        });
        // Extended association rules (token, negative-item, sequential) mined from the training split.
        app.get("/api/extended/rules", ctx -> {
            ctx.json(modelState.getExtendedRules().stream()
                .map(tw.edu.cse.nsysu.ExtendedRuleMiner::toMap).toList());
        });
        // Ablation study results produced by AblationExperiment.
        app.get("/api/extended/ablation", ctx -> serveCsv(ctx, "data/processed/ablation_results.csv",
            "Run AblationExperiment first."));
        // CV and significance results produced by StatisticalValidation.
        app.get("/api/extended/statistics", ctx -> serveCsv(ctx, "data/processed/statistical_results.csv",
            "Run StatisticalValidation first."));
        //Define an endpoint to get the model's warmup status and total number of trained instances
        app.get("/api/incremental/status", ctx -> {
            ctx.json(new IncrementalStatusResponse(
                    modelState.isWarmedUp(),
                    modelState.getTotalTrained(),
                    modelState.getNormalProfileSize(),
                    modelState.getRuleFeatureCount(),
                    modelState.getWarmupLabelCounts(),
                    modelState.getArchitectureThresholds()));
        });
        app.get("/api/incremental/models", ctx -> {
            ctx.json(modelState.getIncrementalModels());
        });
        //Define an endpoint to get the model's accuracy history, returning a list of AccuracyPoint objects with index, accuracy, total evaluated, and timestamp
        app.get("/api/stats/accuracy", ctx -> {
            try {
                ctx.json(modelState.getAccuracyHistory(ctx.queryParam("modelId")));
            } catch (IllegalArgumentException error) {
                ctx.status(400).json(java.util.Map.of("error", error.getMessage()));
            }
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
        // Compare all five architecture paths on the same held-out data.
        app.post("/api/performance/compare", ctx -> {
            try {
                SimulationRequest request = mapper.readValue(ctx.body(), SimulationRequest.class);
                if (request.datasetPath == null || request.sampleSize <= 0) {
                    ctx.status(400).json(java.util.Map.of("error", "Invalid performance request."));
                    return;
                }
                ctx.json(websiteComparison.compare(
                    request, FrozenArchitectureComparisonService.ARCHITECTURES));
            } catch (java.nio.file.NoSuchFileException error) {
                ctx.status(400).json(java.util.Map.of(
                        "error", "Dataset file not found: " + error.getFile()));
            } catch (SecurityException | IllegalArgumentException error) {
                ctx.status(400).json(java.util.Map.of("error", error.getMessage()));
            } catch (Exception error) {
                error.printStackTrace();
                ctx.status(500).json(java.util.Map.of(
                        "error", error.getClass().getSimpleName() + ": " + error.getMessage()));
            }
        });
        // Compare only the two Extended ARM methods on the same held-out data.
        app.post("/api/extended/performance/compare", ctx -> {
            try {
                SimulationRequest request = mapper.readValue(ctx.body(), SimulationRequest.class);
                if (request.datasetPath == null || request.sampleSize <= 0) {
                    ctx.status(400).json(java.util.Map.of("error", "Invalid performance request."));
                    return;
                }
                ctx.json(websiteComparison.compare(request, java.util.List.of(
                    "PURE_EXTENDED_ARM", "EXTENDED_ARM")));
            } catch (java.nio.file.NoSuchFileException error) {
                ctx.status(400).json(java.util.Map.of(
                        "error", "Dataset file not found: " + error.getFile()));
            } catch (SecurityException | IllegalArgumentException error) {
                ctx.status(400).json(java.util.Map.of("error", error.getMessage()));
            } catch (Exception error) {
                error.printStackTrace();
                ctx.status(500).json(java.util.Map.of(
                        "error", error.getClass().getSimpleName() + ": " + error.getMessage()));
            }
        });
        app.post("/api/incremental/algorithms/compare", ctx -> {
            compareIncrementalAlgorithms(
                ctx, mapper, incrementalComparisonService,
                java.util.List.of(
                    "RATIO_INCREMENTAL_ML", "FULL_FLOW", "EXTENDED_ARM"));
        });
        app.post("/api/extended/incremental/algorithms/compare", ctx -> {
            compareIncrementalAlgorithms(
                ctx, mapper, incrementalComparisonService,
                java.util.List.of("EXTENDED_ARM"));
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
            String modelId = tw.edu.cse.nsysu.api.service.IncrementalAlgorithm
                .fromId(request.modelId).getId();
            modelState.applyIncrementalFeedback(
                request.sql, request.verifiedLabel, request.verifiedBenign, modelId);
            ctx.json(java.util.Map.of(
                "updatedMl", true,
                "updatedArm", modelId.equals("CURRENT_ONLINE_BAGGING_NB")
                    && request.verifiedBenign && request.verifiedLabel == 0,
                "modelId", modelId,
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
            "data/processed/Stage4/Version1/FPGrowth_output_minSup_0.05_minConf_0.8.txt"
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
            ctx.json(ruleMiningService.parseRuleFile(filePath));
        });

        System.out.println("API server started at http://localhost:" + app.port());
        // Add a shutdown hook to gracefully shut down the simulation job service when the application is terminated
        Runtime.getRuntime().addShutdownHook(
            new Thread(() -> {
                simulationJobService.shutdown();
                app.stop();
                try {
                    conn.close();
                } catch (Exception failure) {
                    failure.printStackTrace();
                }
            })
        );
    }

    private static void compareIncrementalAlgorithms(
            io.javalin.http.Context ctx,
            ObjectMapper mapper,
            IncrementalAlgorithmComparisonService comparisonService,
            java.util.List<String> architectures
    ) {
        try {
            SimulationRequest request = mapper.readValue(ctx.body(), SimulationRequest.class);
            if (request.datasetPath == null || request.sampleSize <= 0) {
                ctx.status(400).json(java.util.Map.of(
                    "error", "Invalid incremental comparison request."));
                return;
            }
            ctx.json(comparisonService.compare(request, architectures));
        } catch (java.nio.file.NoSuchFileException error) {
            ctx.status(400).json(java.util.Map.of(
                "error", "Dataset file not found: " + error.getFile()));
        } catch (SecurityException | IllegalArgumentException error) {
            ctx.status(400).json(java.util.Map.of("error", error.getMessage()));
        } catch (Exception error) {
            error.printStackTrace();
            ctx.status(500).json(java.util.Map.of(
                "error", error.getClass().getSimpleName() + ": " + error.getMessage()));
        }
    }

    public static void registerExternalReportRoutes(
            Javalin app, ExternalReportService externalReports) {
        app.get("/api/external/reports", ctx -> {
            try {
                ctx.json(java.util.Map.of("reports", externalReports.listReports()));
            } catch (Exception failure) {
                ctx.status(500).json(java.util.Map.of(
                        "error", "Unable to read external reports: " + failure.getMessage()));
            }
        });
        app.get("/api/external/reports/{id}", ctx -> {
            try {
                ctx.json(externalReports.getReport(ctx.pathParam("id")));
            } catch (IllegalArgumentException failure) {
                ctx.status(400).json(java.util.Map.of("error", failure.getMessage()));
            } catch (ExternalReportService.ReportNotFoundException failure) {
                ctx.status(404).json(java.util.Map.of("error", failure.getMessage()));
            } catch (Exception failure) {
                ctx.status(500).json(java.util.Map.of(
                        "error", "Unable to read external report: " + failure.getMessage()));
            }
        });
        app.get("/api/external/reports/{id}/download", ctx -> {
            try {
                byte[] report = externalReports.downloadReport(ctx.pathParam("id"));
                ctx.contentType("application/json; charset=utf-8");
                ctx.header("Content-Disposition", "attachment; filename=\""
                        + ctx.pathParam("id") + "-external-report.json\"");
                ctx.result(report);
            } catch (IllegalArgumentException failure) {
                ctx.status(400).json(java.util.Map.of("error", failure.getMessage()));
            } catch (ExternalReportService.ReportNotFoundException failure) {
                ctx.status(404).json(java.util.Map.of("error", failure.getMessage()));
            } catch (Exception failure) {
                ctx.status(500).json(java.util.Map.of(
                        "error", "Unable to download external report: " + failure.getMessage()));
            }
        });
    }

    public static void registerRuleAblationReportRoutes(
            Javalin app, RuleAblationReportService reports) {
        app.get("/api/experiments/rule-ablation/reports", ctx -> {
            try {
                ctx.json(java.util.Map.of("reports", reports.listReports()));
            } catch (Exception failure) {
                ctx.status(500).json(java.util.Map.of("error",
                        "Unable to read rule ablation reports: " + failure.getMessage()));
            }
        });
        app.get("/api/experiments/rule-ablation/reports/{id}", ctx -> {
            try {
                ctx.json(reports.getReport(ctx.pathParam("id")));
            } catch (IllegalArgumentException failure) {
                ctx.status(400).json(java.util.Map.of("error", failure.getMessage()));
            } catch (ExternalReportService.ReportNotFoundException failure) {
                ctx.status(404).json(java.util.Map.of("error", failure.getMessage()));
            } catch (Exception failure) {
                ctx.status(500).json(java.util.Map.of("error",
                        "Unable to read rule ablation report: " + failure.getMessage()));
            }
        });
        app.get("/api/experiments/rule-ablation/reports/{id}/download", ctx -> {
            try {
                byte[] bytes = reports.downloadReport(ctx.pathParam("id"));
                ctx.contentType("application/json; charset=utf-8");
                ctx.header("Content-Disposition", "attachment; filename=\""
                        + ctx.pathParam("id") + "-ablation-report.json\"");
                ctx.result(bytes);
            } catch (IllegalArgumentException failure) {
                ctx.status(400).json(java.util.Map.of("error", failure.getMessage()));
            } catch (ExternalReportService.ReportNotFoundException failure) {
                ctx.status(404).json(java.util.Map.of("error", failure.getMessage()));
            } catch (Exception failure) {
                ctx.status(500).json(java.util.Map.of("error",
                        "Unable to download rule ablation report: " + failure.getMessage()));
            }
        });
    }

    private static void serveCsv(io.javalin.http.Context ctx, String file, String missingMessage) throws Exception {
        java.nio.file.Path csv = java.nio.file.Path.of(file);
        if (!java.nio.file.Files.exists(csv)) {
            ctx.status(404).json(java.util.Map.of("error", missingMessage));
            return;
        }
        java.util.List<String> lines = java.nio.file.Files.readAllLines(csv);
        String[] header = lines.get(0).split(",");
        java.util.List<java.util.Map<String, String>> rows = new java.util.ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", header.length);
            java.util.Map<String, String> row = new java.util.LinkedHashMap<>();
            for (int k = 0; k < header.length && k < cells.length; k++) row.put(header[k], cells[k]);
            rows.add(row);
        }
        ctx.json(rows);
    }
}
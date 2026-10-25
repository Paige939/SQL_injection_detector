package tw.edu.cse.nsysu.api.service;

import tw.edu.cse.nsysu.api.dto.DetectResponse;
import tw.edu.cse.nsysu.api.dto.IncrementalFeedRequest;
import tw.edu.cse.nsysu.api.dto.IncrementalFeedResponse;
// A service class that handles incremental feeding of new data samples into the detection model, validating input, detecting labels and confidence, updating the model with true labels, and returning responses with prediction results and model state.
public class IncrementalService {
    private final DetectionService detectionService;
    private final ModelStateService modelState;
    private final DeployedModels deployment;

    public IncrementalService(
            DetectionService detectionService,
            ModelStateService modelState
    ) {
        this.detectionService = detectionService;
        this.modelState = modelState;
        this.deployment = null;
    }

    public IncrementalService(DeployedModels deployment) {
        this.modelState = deployment.interactive();
        this.detectionService = new DetectionService(modelState);
        this.deployment = deployment;
    }
    // Processes an incremental feed request, validating the input, detecting the label and confidence of the SQL query, optionally updating the model with the true label, and returning a response containing the predicted label, confidence score, whether the model was updated, total number of samples trained, and the features used for prediction.
    public IncrementalFeedResponse process(
            IncrementalFeedRequest request
    ) throws Exception {
        SingleInputValidation.requireValid(request.sql);
        // Validate the label if provided, ensuring it is either 0 or 1.
        if (request.label != null &&
            request.label != 0 &&
            request.label != 1) {
            throw new IllegalArgumentException("Label must be 0 or 1.");
        }

        DetectResponse detection = detectionService.detect(request.sql, request.modelId);
        String modelId = tw.edu.cse.nsysu.api.service.IncrementalAlgorithm
            .fromId(request.modelId).getId();
        boolean modelUpdated = false;
        // If a true label is provided, record the evaluation and update the model with the new data sample.
        if (request.label != null) {
            modelState.recordEvaluation(
                detection.label,
                request.label,
                modelId
            );
            if (deployment != null) {
                deployment.feedback(request.sql, request.label, request.label == 0, modelId);
            } else modelState.applyIncrementalFeedback(
                request.sql,
                request.label,
                request.label == 0,
                modelId
            );

            modelUpdated = true;
        }
        // Return the response containing the predicted label, confidence score, whether the model was updated, total number of samples trained, and the features used for prediction.
        IncrementalFeedResponse response = new IncrementalFeedResponse(
            detection.label,
            detection.confidence,
            modelUpdated,
            modelState.getIncrementalModels().stream()
                .filter(model -> model.get("id").equals(modelId))
                .map(model -> (Integer) model.get("totalTrained"))
                .findFirst().orElseThrow(),
            detection.features,
            modelId
        );
        response.score = detection.score;
        response.threshold = detection.threshold;
        response.modelMode = detection.modelMode;
        response.checkpointSha256 = detection.checkpointSha256;
        return response;
    }
}
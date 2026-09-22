package tw.edu.cse.nsysu.api.service;

import tw.edu.cse.nsysu.api.dto.DetectResponse;
import tw.edu.cse.nsysu.api.dto.IncrementalFeedRequest;
import tw.edu.cse.nsysu.api.dto.IncrementalFeedResponse;
// A service class that handles incremental feeding of new data samples into the detection model, validating input, detecting labels and confidence, updating the model with true labels, and returning responses with prediction results and model state.
public class IncrementalService {
    private final DetectionService detectionService;
    private final ModelStateService modelState;

    public IncrementalService(
            DetectionService detectionService,
            ModelStateService modelState
    ) {
        this.detectionService = detectionService;
        this.modelState = modelState;
    }
    // Processes an incremental feed request, validating the input, detecting the label and confidence of the SQL query, optionally updating the model with the true label, and returning a response containing the predicted label, confidence score, whether the model was updated, total number of samples trained, and the features used for prediction.
    public IncrementalFeedResponse process(
            IncrementalFeedRequest request
    ) throws Exception {
        // Validate the SQL query, ensuring it is not null or blank to prevent processing invalid input.
        if (request.sql == null || request.sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be empty.");
        }
        // Validate the SQL query length, ensuring it does not exceed 4000 characters to prevent excessively long queries.
        if (request.sql.length() > 4000) {
            throw new IllegalArgumentException("SQL exceeds 4000 characters.");
        }
        // Validate the label if provided, ensuring it is either 0 or 1.
        if (request.label != null &&
            request.label != 0 &&
            request.label != 1) {
            throw new IllegalArgumentException("Label must be 0 or 1.");
        }

        DetectResponse detection = detectionService.detect(request.sql);
        boolean modelUpdated = false;
        // If a true label is provided, record the evaluation and update the model with the new data sample.
        if (request.label != null) {
            modelState.recordEvaluation(
                detection.label,
                request.label
            );
            // Update the model with the new data sample, converting the SQL query into features and using the provided label.
            modelState.update(
                modelState.toFeature(request.sql),
                request.label
            );

            modelUpdated = true;
        }
        // Return the response containing the predicted label, confidence score, whether the model was updated, total number of samples trained, and the features used for prediction.
        return new IncrementalFeedResponse(
            detection.label,
            detection.confidence,
            modelUpdated,
            modelState.getTotalTrained(),
            detection.features
        );
    }
}
package tw.edu.cse.nsysu.api.dto;

import java.util.Map;
// A simple data transfer object (DTO) that represents the response after incrementally feeding a new data sample into the model, including the predicted label, confidence score, whether the model was updated, total number of samples trained, and the features used for prediction.
public class IncrementalFeedResponse {
    public int predictedLabel;
    public double confidence;
    public boolean modelUpdated;
    public int totalTrained;
    public Map<String, Double> features;
    public String modelId;
    public double score;
    public double threshold;
    public String modelMode;
    public String checkpointSha256;
    
    public IncrementalFeedResponse(
            int predictedLabel,
            double confidence,
            boolean modelUpdated,
            int totalTrained,
            Map<String, Double> features,
            String modelId
    ) {
        this.predictedLabel = predictedLabel;
        this.confidence = confidence;
        this.modelUpdated = modelUpdated;
        this.totalTrained = totalTrained;
        this.features = features;
        this.modelId = modelId;
    }
}
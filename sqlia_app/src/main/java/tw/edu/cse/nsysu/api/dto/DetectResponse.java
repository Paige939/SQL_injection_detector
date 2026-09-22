package tw.edu.cse.nsysu.api.dto;

import java.util.Map;

public class DetectResponse {
    public int label; //0:benign, 1:malicious
    public double confidence;
    public Map<String, Double> features; //Six extracted features and their meanings.
    public DetectResponse(int label, double confidence, Map<String, Double> features) {
        this.label = label;
        this.confidence = confidence;
        this.features = features;
    }
}
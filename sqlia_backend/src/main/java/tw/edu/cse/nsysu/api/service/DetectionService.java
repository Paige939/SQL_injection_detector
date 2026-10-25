package tw.edu.cse.nsysu.api.service;

import tw.edu.cse.nsysu.ExtendedRuleMiner;
import tw.edu.cse.nsysu.FeatureExtract;
import tw.edu.cse.nsysu.api.dto.DetectResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public class DetectionService{
    //The names of the features extracted from the raw SQL
    private static final String[] FEATURE_NAMES = {
        "payloadLength", "symbolDensity", "comparisonDensity",
        "complexity", "functionDensity", "discontinuousDensity"
    };
    //The model state service used for feature extraction and prediction
    private final ModelStateService modelState;
    public DetectionService(ModelStateService modelState){
        this.modelState = modelState;
    }
    //Detect the label, confidence, and features for the given raw SQL using the model state service
    public DetectResponse detect(String rawSql) throws Exception{
        return detect(rawSql, null);
    }

    public DetectResponse detect(String rawSql, String modelId) throws Exception{
        SingleInputValidation.requireValid(rawSql);
        double[] base = FeatureExtract.extract(rawSql);
        double[] prob = modelState.predictIncrementalProb(rawSql, modelId);
        double threshold = modelState.incrementalThreshold(modelId);
        int label = prob[1] >= threshold ? 1 : 0;
        double confidence = prob[label];
        Map<String, Double> features = new LinkedHashMap<>();
        for(int i = 0; i < base.length && i < FEATURE_NAMES.length; i++){
            features.put(FEATURE_NAMES[i], base[i]);
        }
        DetectResponse response = new DetectResponse(label, confidence, features);
        response.score = prob[1];
        response.threshold = threshold;
        response.modelMode = modelState.getModelMode();
        response.checkpointSha256 = modelState.getCheckpointHash();
        return response;
    }

    // Predict through one of the named architecture paths used by benchmarking.
    public int predictArchitecture(String rawSql, String architecture) throws Exception {
        SingleInputValidation.requireValid(rawSql);
        return modelState.predictArchitecture(rawSql, architecture);
    }

    // Predict one request through all architecture paths for side-by-side UI comparison.
    public List<Map<String, Object>> predictAllArchitectures(String rawSql) throws Exception {
        SingleInputValidation.requireValid(rawSql);
        return modelState.predictAllSnapshot(rawSql);
    }

    public List<Map<String, Object>> predictExtendedArchitectures(String rawSql) throws Exception {
        SingleInputValidation.requireValid(rawSql);
        return modelState.predictSnapshot(
            rawSql, List.of("PURE_EXTENDED_ARM", "EXTENDED_ARM"));
    }
}

package tw.edu.cse.nsysu.api.service;

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
        double[] base = FeatureExtract.extract(rawSql);
        float[] x = modelState.toFeature(rawSql);
        int label = modelState.predict(x);
        double[] prob = modelState.predictProb(x);
        double confidence = prob[label];
        Map<String, Double> features = new LinkedHashMap<>();
        for(int i = 0; i < base.length && i < FEATURE_NAMES.length; i++){
            features.put(FEATURE_NAMES[i], base[i]);
        }
        return new DetectResponse(label, confidence, features);
    }

    // Predict through one of the named architecture paths used by benchmarking.
    public int predictArchitecture(String rawSql, String architecture) throws Exception {
        return modelState.predictArchitecture(rawSql, architecture);
    }

    // Predict one request through all architecture paths for side-by-side UI comparison.
    public List<Map<String, Object>> predictAllArchitectures(String rawSql) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String architecture : List.of("ARM_PROFILE", "RATIO_INCREMENTAL_ML", "FULL_FLOW")) {
            double[] probability = modelState.predictArchitectureProb(rawSql, architecture);
            int label = probability[1] >= probability[0] ? 1 : 0;
            results.add(Map.of(
                    "architecture", architecture,
                    "label", label,
                    "confidence", probability[label],
                    "benignProbability", probability[0],
                    "maliciousProbability", probability[1]));
        }
        return results;
    }
}
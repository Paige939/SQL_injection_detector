package tw.edu.cse.nsysu.api.dto;

public class SimulationRequest{
    public String datasetPath;
    public int sampleSize = 200; //Size of sampling data, default is 200
    public boolean updateModel = false; //Whether to update the model, default is false
    public String architecture; // Includes PURE_EXTENDED_ARM, which is evaluated without online updates.
    public String modelId;
    public String checkpointDirectory; // Optional trusted local experiment; no retraining when provided.
}
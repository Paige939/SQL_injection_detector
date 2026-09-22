package tw.edu.cse.nsysu.api.dto;

public class SimulationRequest{
    public String datasetPath;
    public int sampleSize = 200; //Size of sampling data, default is 200
    public boolean updateModel = false; //Whether to update the model, default is false
    public String architecture; //ARM_PROFILE, RATIO_INCREMENTAL_ML, or FULL_FLOW
}
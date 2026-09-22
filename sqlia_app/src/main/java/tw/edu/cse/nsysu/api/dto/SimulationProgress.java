package tw.edu.cse.nsysu.api.dto;
// A simple data transfer object (DTO) that represents the progress of a simulation, including the total number of processed samples and the total number of samples in the simulation.
public class SimulationProgress{
    public int totalProcessed;
    public int totalSamples;
    public SimulationProgress(int totalProcessed, int totalSamples){
        this.totalProcessed = totalProcessed;
        this.totalSamples = totalSamples;
    }
}
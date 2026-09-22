package tw.edu.cse.nsysu.api.dto;

public class SimulationResult{
    public int total, tp, fp, tn, fn;
    public double accuracy, precision, recall, f1;
    public long durationMs; //Duration of simulation in milliseconds
}
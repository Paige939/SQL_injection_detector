package tw.edu.cse.nsysu.api.dto;
// A simple data transfer object (DTO) that represents a simulation job, including its ID, status, progress, error message, and result.
public class SimulationJob{
    public String id;
    public String status;
    public int processed;
    public int total;
    public String error;
    public SimulationResult result;

    public SimulationJob(String id) {
        this.id = id;
        this.status = "QUEUED";
    }
}
package tw.edu.cse.nsysu.api.dto;
// A simple data transfer object (DTO) that represents a request to incrementally feed a new data sample into the model, including the SQL query for the sample and its corresponding label.
public class IncrementalFeedRequest {
    public String sql;
    public Integer label;
}
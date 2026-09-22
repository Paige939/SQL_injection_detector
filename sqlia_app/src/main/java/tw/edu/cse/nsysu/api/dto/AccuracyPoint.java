package tw.edu.cse.nsysu.api.dto;

public class AccuracyPoint{
    public int index;
    public double accuracy;
    public int totalEvaluated;
    public String timeStamp;
    public AccuracyPoint(int index, double accuracy, int totalEvaluated, String timeStamp){
        this.index = index;
        this.accuracy = accuracy;
        this.totalEvaluated = totalEvaluated;
        this.timeStamp = timeStamp;
    }
}
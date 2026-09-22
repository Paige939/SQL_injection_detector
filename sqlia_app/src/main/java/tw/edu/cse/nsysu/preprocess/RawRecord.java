package tw.edu.cse.nsysu.preprocess;

//The unified format of CSV files
public class RawRecord {
    public String rawText; //raw sql/ payload
    public String rawLabel; //raw label (not validate with either 0 or 1)
    public String source; //source dataset name
    public boolean benignValid = true; //Examine if benign is a valid SQL/HTTP request

    public RawRecord(String rawText, String rawLabel, String source) {
        this.rawText = rawText;
        this.rawLabel = rawLabel;
        this.source = source;
    }
}
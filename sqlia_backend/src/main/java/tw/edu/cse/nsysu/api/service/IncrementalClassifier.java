package tw.edu.cse.nsysu.api.service;

public interface IncrementalClassifier {
    void initialize(int featureCount) throws Exception;

    double[] predictProb(float[] features) throws Exception;

    void update(float[] features, int label) throws Exception;

    int getTotalTrained();
}

package tw.edu.cse.nsysu.api.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class SimulationResult{
    public int total, tp, fp, tn, fn;
    public double accuracy, precision, recall, f1, fpr;
    public long durationMs; //Duration of simulation in milliseconds
    public String evaluationMode;
    public Map<String, GroupMetrics> bySource = new LinkedHashMap<>();
    public Map<String, GroupMetrics> byInputType = new LinkedHashMap<>();

    public static class GroupMetrics {
        public int total, tp, fp, tn, fn;
        public double accuracy, precision, recall, f1, fpr;

        public void add(int prediction, int actual) {
            if (prediction == 1 && actual == 1) tp++;
            else if (prediction == 1) fp++;
            else if (actual == 0) tn++;
            else fn++;
            total = tp + fp + tn + fn;
            accuracy = total == 0 ? 0 : (double) (tp + tn) / total;
            precision = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
            recall = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
            f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
            fpr = fp + tn == 0 ? 0 : (double) fp / (fp + tn);
        }
    }
}
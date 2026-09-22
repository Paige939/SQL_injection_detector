package tw.edu.cse.nsysu.discretize;

public class SupportCalculator {
    //minSupport = max(0.02, 20 / N), N is transaction number
    public static double computeMinSupport(int transactionCount) {
        double dynamicSupport = 20.0 / transactionCount;
        return Math.max(0.02, dynamicSupport);
    }
    //Transfer the minimum support ratio to the minimum count for SPMF
    public static int computeMinCount(int transactionCount, double minSupportRatio) {
        return (int) Math.max(5, Math.ceil(transactionCount * minSupportRatio));
    }
}
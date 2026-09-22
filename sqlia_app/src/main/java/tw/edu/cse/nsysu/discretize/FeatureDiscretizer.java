package tw.edu.cse.nsysu.discretize;

import java.util.ArrayList;
import java.util.List;

public class FeatureDiscretizer {
    //baseVector corresponds to FeatureExtract's 6-dimension features
    //[0]:lenght, [1]:symbolDensity, [2]:comparisonDensity, [3]:complexity, [4]:functionDensity, [5]:discontinuousDensity
    public static List<String> toTransaction(double[] f, QuantileThresholds t) {
        List<String> items = new ArrayList<>();
        items.add("length=" + bin(f[0], t.lengthQ50, t.lengthQ90, "SHORT", "MEDIUM", "LONG"));
        items.add("symbolDensity=" + bin(f[1], t.symbolQ50, t.symbolQ90, "LOW", "MEDIUM", "HIGH"));
        //comparison / function density has high probability to be 0, so divide into ZERO/NONZERO, then HIGH/LOW
        items.add("comparisonDensity=" + zeroAwareBin(f[2], t.comparisonQ90));
        items.add("functionDensity=" + zeroAwareBin(f[4], t.functionQ90));
        //complexity has only 0-3, use directly
        items.add("complexity=" + (int) f[3]);
        items.add("discontinuousDensity=" + bin(f[5], t.discontinuousQ50, t.discontinuousQ90, "LOW", "MEDIUM", "HIGH"));
        return items;
    }
    // Helper method to bin a value into LOW, MEDIUM, or HIGH based on the provided quantile thresholds
    private static String bin(double value, double q50, double q90, String lowLabel, String mediumLabel, String highLabel) {
        if (value <= q50) return lowLabel;
        else if (value <= q90) return mediumLabel;
        else return highLabel;
    }
    // Helper method to bin a value into ZERO, NONZERO_LOW, or NONZERO_HIGH based on the provided quantile threshold
    private static String zeroAwareBin(double value, double q90) {
        if (value == 0) return "ZERO";
        else if (value <= q90) return "NONZERO_LOW";
        else return "NONZERO_HIGH";
    }
}
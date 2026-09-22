package tw.edu.cse.nsysu.discretize;

import java.util.List;
import java.util.Set;

public class BenignSupportCalculator {
    //supportB(X) = benign transaction count containing X / benign transaction count
    //Only count verified benign data (for updating ARM normal profile)
    public static double benignSupport(List<Set<String>> benignTransactions, Set<String> itemset) {
        if (benignTransactions.isEmpty()) return 0.0;
        long matched = benignTransactions.stream()
                .filter(tx -> tx.containsAll(itemset))
                .count();
        return (double) matched / benignTransactions.size();
    }
}


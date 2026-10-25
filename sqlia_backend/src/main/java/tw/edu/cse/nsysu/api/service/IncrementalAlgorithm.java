package tw.edu.cse.nsysu.api.service;

import java.util.List;

public enum IncrementalAlgorithm {
    CURRENT_ONLINE_BAGGING_NB(
        "CURRENT_ONLINE_BAGGING_NB",
        "Current Online Bagging (Naive Bayes)"
    ),
    SGD("SGD", "SGD / Incremental SVM"),
    HOEFFDING_TREE("HOEFFDING_TREE", "Hoeffding Tree");

    private final String id;
    private final String displayName;

    IncrementalAlgorithm(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static IncrementalAlgorithm fromId(String id) {
        if (id == null || id.isBlank()) {
            return CURRENT_ONLINE_BAGGING_NB;
        }
        for (IncrementalAlgorithm algorithm : values()) {
            if (algorithm.id.equalsIgnoreCase(id.trim())) {
                return algorithm;
            }
        }
        throw new IllegalArgumentException("Unknown incremental model: " + id);
    }

    public static List<IncrementalAlgorithm> available() {
        return List.of(values());
    }
}

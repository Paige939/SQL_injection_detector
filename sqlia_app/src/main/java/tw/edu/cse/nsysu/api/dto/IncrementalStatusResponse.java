package tw.edu.cse.nsysu.api.dto;

import java.util.Map;

public class IncrementalStatusResponse {
    public boolean isWarmUp;
    public int totalTrained;
    public int normalProfileSize;
    public int fpGrowthRuleCount;
    public Map<Integer, Integer> warmupLabelCounts;
    public Map<String, Double> architectureThresholds;

    public IncrementalStatusResponse(
            boolean isWarmUp,
            int totalTrained,
            int normalProfileSize,
            int fpGrowthRuleCount,
            Map<Integer, Integer> warmupLabelCounts,
            Map<String, Double> architectureThresholds
    ) {
        this.isWarmUp = isWarmUp;
        this.totalTrained = totalTrained;
        this.normalProfileSize = normalProfileSize;
        this.fpGrowthRuleCount = fpGrowthRuleCount;
        this.warmupLabelCounts = warmupLabelCounts;
        this.architectureThresholds = architectureThresholds;
    }
}
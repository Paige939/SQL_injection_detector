package tw.edu.cse.nsysu;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FeatureForML{
    // FeatureExtract returns [length, symbol density, comparison density, complexity, function density, discontinuous density].
    public static final int BASE_FEATURE_COUNT = 6;
    private static final double LENGTH_HIGH = 100.0;
    private static final double SYMBOL_HIGH = 0.30;
    private static final double COMPARISON_HIGH = 0.05;
    private static final double FUNCTION_HIGH = 0.02;
    private static final double DISCONTINUOUS_HIGH = 0.30;
    //Define association rules structure
    public static class Rule{
        int[] antecedent;
        int consequent;
        double confidence;
        public Rule(int[] ant, int cons, double conf){
            this.antecedent=ant; //For simplicity, we only consider the first item in the antecedent
            this.consequent=cons;
            this.confidence=conf;
        }
    }
    private List<int[]> activePatterns = new ArrayList<>();
    private List<Rule> activeRules = new ArrayList<>();
    //Constructor for different senarios
    public FeatureForML(){}
    public FeatureForML(List<int[]> patterns){
        this.activePatterns=patterns;
    }
    public void setRules(List<Rule> rules){
        this.activeRules=rules;
    }
    //A method to transfer to transaction list
    public static Set<Integer> toTransactionSet(double[] baseVector){
        if (baseVector == null || baseVector.length != BASE_FEATURE_COUNT) {
            throw new IllegalArgumentException("Expected six base features.");
        }
        //Convert each continuous feature into a stable categorical item for ARM.
        Set<Integer> transactionSet=new HashSet<>();
        if (baseVector[0] > LENGTH_HIGH) transactionSet.add(1);
        if (baseVector[1] > SYMBOL_HIGH) transactionSet.add(2);
        if (baseVector[2] == 0.0) transactionSet.add(3);
        else if (baseVector[2] > COMPARISON_HIGH) transactionSet.add(33);
        else transactionSet.add(30);
        transactionSet.add(40 + (int) Math.max(0, Math.min(3, baseVector[3])));
        if (baseVector[4] == 0.0) transactionSet.add(5);
        else if (baseVector[4] > FUNCTION_HIGH) transactionSet.add(55);
        else transactionSet.add(50);
        if (baseVector[5] > DISCONTINUOUS_HIGH) transactionSet.add(6);
        return transactionSet;
    }
    //A method to dynamically generate feature vector
    public float[] getFeatureFromBase(double[] baseVector, int situation){
        List<Float> featureList = new ArrayList<>();
        for (double val : baseVector) {
            featureList.add((float) val);
        }
        //Situation 1: Base features extracted vector only
        if (situation == 1) {
            return toFloatArray(featureList);
        }
        Set<Integer> transactionSet = toTransactionSet(baseVector);
        switch(situation){
            case 2: //Situation 2: Base features + patterns (minSup=0.05)
            case 3: //Situation 3: Base features + patterns (minSup=0.1)
                for(int[] pattern: activePatterns)
                    featureList.add(matchesPattern(transactionSet, pattern)? 1.0f: 0.0f);
                break;
            case 4: //Situation 4: Base features + rules (minSup=0.1, minConf=0.6)
            case 5: //Situation 5: Base features + rules (minSup=0.05, minConf=0.6)
                for(Rule r: activeRules)
                    featureList.add(matchesRule(transactionSet, r)? (float)r.confidence: 0.0f);
                break;
            case 6: //Situation 6: Base features + rules (minSup=0.05, minConf=0.8)
            case 7: //Situation 7: Base features + rules (minSup=0.1, minConf=0.8)
                break;
        }
        return toFloatArray(featureList); //Since there is no rule meets minConf=0.8, the feature vector will be the same as situation 1
    }

    //Helper method to check if the transaction set matches a given pattern
    private boolean matchesPattern(Set<Integer> transactionSet, int[] pattern){
        for(int item: pattern){
            if(!transactionSet.contains(item))
                return false;
        }
        return true;
    }
    //Helper method to check if the transaction set matches a given rule
    private boolean matchesRule(Set<Integer> transactionSet, Rule rule){
        for(int item: rule.antecedent){
            if(!transactionSet.contains(item))
                return false;
        }
        return transactionSet.contains(rule.consequent);
    }
    //Helper method to convert List<Float> to float[][]
    private float[] toFloatArray(List<Float> featureList){
        float[] array=new float[featureList.size()];
        for(int i=0; i<featureList.size(); i++){
            array[i]=featureList.get(i);
        }
        return array;   
    }
}
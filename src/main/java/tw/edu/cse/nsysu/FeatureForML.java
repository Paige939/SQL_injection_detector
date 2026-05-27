package tw.edu.cse.nsysu;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

public class FeatureForML{
    //Set transaction list thresholds
    public static final double LONG_LEN=100.0;
    public static final double EQ_THRES=3.0;
    public static final double WHERE_THRES=1.0;
    public static final double SELECT_THRES=1.0;
    public static final double UNION_THRES=1.0;
    public static final double QUOTE_THRES=2.0;
    public static final double COMMENT_THRES=0.0;
    public static final double OR_ACCONT=0.2;
    //Situation2: Patterns (minSup=0.05)
    private static final int[][] PATTERNS1={
        {5, 4},
        {44, 1},
        {44, 3},
        {1, 3},
        {1, 4},
        {3, 4}
    };
    //Situation3: Patterns (minSup=0.1)
    private static final int[][] PATTERNS2={
       {1, 4}, 
       {3, 4}
    };
    //Define association rules structure
    private static class Rule{
        int[] antecedent;
        int consequent;
        double confidence;
        Rule(int[] ant, int cons, double conf){
            this.antecedent=ant; //For simplicity, we only consider the first item in the antecedent
            this.consequent=cons;
            this.confidence=conf;
        }
    }
    //Situation4: Rules (minSup=0.1, minConf=0.6)
    private static final Rule[] RULE1={
        new Rule(new int[]{3}, 4, 0.6467151544056278)
    };
    //Situation5: Rules (minSup=0.05, minConf=0.6)
    private static final Rule[] RULE2={
        new Rule(new int[]{3}, 4, 0.6467151544056278),
        new Rule(new int[]{5}, 4, 0.7067789811472122),
    };
    //Situation6: Rules (minSup=0.05, minConf=0.8)
    //Situation7: Rules (minSup=0.1, minConf=0.8)

    //Generate the corresponding feature vector according to 1~7 stuations
    public float[] getFeature(String rawSQL, int situation){
        //Extract base features from Stage 2's feature extraction
        double[] baseVector=FeatureExtract.extract(rawSQL);
        List<Float> featureList=new ArrayList<>();
        for(double val: baseVector){
            featureList.add((float)val);
        }
        //Situation 1: Base features extracted vector only
        if(situation==1){
            return toFloatArray(featureList);
        }
        
        //Construct SQL attack transaction ID set for the given situation
        Set<Integer> transactionSet=new HashSet<>();
        if (baseVector[0]>LONG_LEN) transactionSet.add(1);
        if (baseVector[1]>EQ_THRES) transactionSet.add(2);
        if (baseVector[2]==WHERE_THRES) transactionSet.add(3);
        else if (baseVector[2]>WHERE_THRES) transactionSet.add(33);
        if (baseVector[3]==SELECT_THRES) transactionSet.add(4);
        else if (baseVector[3]>SELECT_THRES) transactionSet.add(44);
        if (baseVector[4]==UNION_THRES) transactionSet.add(5);
        else if (baseVector[4]>UNION_THRES) transactionSet.add(55);
        if (baseVector[5]>QUOTE_THRES) transactionSet.add(6);
        if (baseVector[6]>COMMENT_THRES) transactionSet.add(7);
        if (baseVector[7]>OR_ACCONT) transactionSet.add(8);

        switch(situation){
            case 2: //Situation 2: Base features + patterns (minSup=0.05)
                for(int[] pattern: PATTERNS1)
                    featureList.add(matchesPattern(transactionSet, pattern)? 1.0f: 0.0f);
                break;
            case 3: //Situation 3: Base features + patterns (minSup=0.1)
                for(int[] pattern: PATTERNS2)
                    featureList.add(matchesPattern(transactionSet, pattern)? 1.0f: 0.0f);
                break;
            case 4: //Situation 4: Base features + rules (minSup=0.1, minConf=0.6)
                for(Rule r: RULE1)
                    featureList.add(matchesRule(transactionSet, r)? (float)r.confidence: 0.0f);
                break;
            case 5: //Situation 5: Base features + rules (minSup=0.05, minConf=0.6)
                for(Rule r: RULE2)
                    featureList.add(matchesRule(transactionSet, r)? (float)r.confidence: 0.0f);
                break;
            case 6: //Situation 6: Base features + rules (minSup=0.05, minConf=0.8)
            case 7: //Situation 7: Base features + rules (minSup=0.1, minConf=0.8)
                return toFloatArray(featureList); //Since there is no rule meets minConf=0.8, the feature vector will be the same as situation 1
        }
        return toFloatArray(featureList);
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
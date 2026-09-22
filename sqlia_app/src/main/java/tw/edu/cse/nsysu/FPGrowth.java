package tw.edu.cse.nsysu;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import ca.pfv.spmf.algorithms.frequentpatterns.fpgrowth.AlgoFPGrowth;
import ca.pfv.spmf.algorithms.associationrules.agrawal94_association_rules.AlgoAgrawalFaster94;
import ca.pfv.spmf.patterns.itemset_array_integers_with_count.Itemsets;
import ca.pfv.spmf.patterns.itemset_array_integers_with_count.Itemset;

public class FPGrowth{
    public void runFPGrowth(String inputPath, String outputPath, double minSup, double minConf){
        try{
            //Construct the FP-Growth algorithm object
            AlgoFPGrowth algo = new AlgoFPGrowth();
            //Run the FP-Growth algorithm to find frequent itemsets from the input transaction data
            Itemsets patterns = algo.runAlgorithm(inputPath, null, minSup);
            if(patterns!=null){
                System.out.println("Patterns found: " + patterns.getItemsetsCount());
                //Use Agrowal94's algorithm to generate association rules from the frequent itemsets found by FP-Growth
                AlgoAgrawalFaster94 algoRules = new AlgoAgrawalFaster94();
                algoRules.runAlgorithm(patterns, outputPath, algo.getDatabaseSize(), minConf);
                System.out.println("-- SPMF end, result in: " + outputPath + " --");
            }else{
                System.out.println("No patterns found with the given minimum support threshold: " + minSup);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    //For scenario 2-3 find out patterns
    public List<int[]> getFrequentPatterns(String inputPath, double minSup) {
        List<int[]> result = new ArrayList<>();
        try {
            AlgoFPGrowth algo = new AlgoFPGrowth();
            Itemsets patterns = algo.runAlgorithm(inputPath, null, minSup);
            if (patterns != null && patterns.getLevels() != null) {
                for (List<Itemset> level : patterns.getLevels()) {
                    if (level == null) continue;
                    for (Itemset itemset : level) {
                        // itemset.getItems() 會直接回傳 int[]
                        result.add(itemset.getItems());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error mining patterns: " + e.getMessage());
        }
        return result;
    }
    //For Senario 4-7 to find out associate rules
    public List<FeatureForML.Rule> getAssociationRules(String inputPath, double minSup, double minConf) {
        List<FeatureForML.Rule> rulesList = new ArrayList<>();
        File tempOutputFile = null;
        try {
            //Construct a temp txt to let SPMF write in the result
            tempOutputFile = File.createTempFile("spmf_rules_", ".txt");
            tempOutputFile.deleteOnExit();

            AlgoFPGrowth algo = new AlgoFPGrowth();
            Itemsets patterns = algo.runAlgorithm(inputPath, null, minSup);
            if (patterns != null) {
                AlgoAgrawalFaster94 algoRules = new AlgoAgrawalFaster94();
                algoRules.runAlgorithm(patterns, tempOutputFile.getAbsolutePath(), algo.getDatabaseSize(), minConf);

                //Read and analysis spmf txt result
                // SPMF format: 3 ==> 4 #SUP: 123 #CONF: 0.6467
                try (BufferedReader br = new BufferedReader(new FileReader(tempOutputFile))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        
                        String[] parts = line.split("==>");
                        if (parts.length < 2) continue;

                        // 解析前項 (Antecedent)
                        String[] antStr = parts[0].trim().split(" ");
                        int[] antecedent = new int[antStr.length];
                        for (int i = 0; i < antStr.length; i++) {
                            antecedent[i] = Integer.parseInt(antStr[i]);
                        }

                        // 解析後項 (Consequent) 與信賴度 (Confidence)
                        String[] rightParts = parts[1].split("#CONF:");
                        int consequent = Integer.parseInt(rightParts[0].trim().split(" ")[0]);
                        double confidence = Double.parseDouble(rightParts[1].trim());

                        rulesList.add(new FeatureForML.Rule(antecedent, consequent, confidence));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error mining rules: " + e.getMessage());
        } finally {
            if (tempOutputFile != null && tempOutputFile.exists()) {
                tempOutputFile.delete(); // release temp file
            }
        }
        return rulesList;
    }
}
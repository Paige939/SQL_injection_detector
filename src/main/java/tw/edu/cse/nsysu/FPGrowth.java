package tw.edu.cse.nsysu;

import java.io.IOException;
import ca.pfv.spmf.algorithms.frequentpatterns.fpgrowth.AlgoFPGrowth;
import ca.pfv.spmf.algorithms.associationrules.agrawal94_association_rules.AlgoAgrawalFaster94;
import ca.pfv.spmf.patterns.itemset_array_integers_with_count.Itemsets;

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
}
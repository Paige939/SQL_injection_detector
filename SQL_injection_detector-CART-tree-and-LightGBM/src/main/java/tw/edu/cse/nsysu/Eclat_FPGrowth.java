package tw.edu.cse.nsysu;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import ca.pfv.spmf.algorithms.frequentpatterns.eclat.AlgoEclat;
import ca.pfv.spmf.algorithms.associationrules.agrawal94_association_rules.AlgoAgrawalFaster94;
import ca.pfv.spmf.patterns.itemset_array_integers_with_count.Itemsets;
import ca.pfv.spmf.input.transaction_database_list_integers.TransactionDatabase;

public class Eclat_FPGrowth{
    public void runEclat_FPGrowth(String inputPath, String outputPath, double minSup, double minConf) {
        try {
            //Load the transaction database from input file path
            TransactionDatabase database = new TransactionDatabase();
            try{
                database.loadFile(inputPath);
                System.out.println("---Transaction database loaded from:"+inputPath+"---");
            } catch (UnsupportedEncodingException e) {
                System.out.println("Error in reading file " + inputPath + ": " + e.getMessage());
                return;
            }
            int dataSize = database.size();
            AlgoEclat eclat = new AlgoEclat();
            //Run the Eclat algorithm to find frequent itemsets from the input transaction data
            Itemsets patterns = eclat.runAlgorithm(null, database, minSup, true);
            if(patterns!=null){
                System.out.println("Patterns found: " + patterns.getItemsetsCount());
            //Use Agrowal94's algorithm to generate association rules from the frequent itemsets found by Eclat
            AlgoAgrawalFaster94 algoRules = new AlgoAgrawalFaster94();
            algoRules.runAlgorithm(patterns, outputPath, dataSize, minConf);
            System.out.println("-- SPMF end, result in: " + outputPath + " --");
            }else{
                System.out.println("No patterns found with the given minimum support threshold: " + minSup);
            }
        } catch (IOException e) {
            System.err.println("file error : " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("SPMF error : " + e.getMessage());
            e.printStackTrace();
        }
    }
}
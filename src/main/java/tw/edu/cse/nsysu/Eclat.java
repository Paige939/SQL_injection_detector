package tw.edu.cse.nsysu;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import ca.pfv.spmf.algorithms.frequentpatterns.eclat.AlgoEclat;
import ca.pfv.spmf.input.transaction_database_list_integers.TransactionDatabase;

public class Eclat{
    public void EclatRunner(String inputPath, String outputPath){
        try{
            //Load the transaction database from input file path
            TransactionDatabase database = new TransactionDatabase();
            try{
                database.loadFile(inputPath);
                System.out.println("---Transaction database loaded from:"+inputPath+"---");
            } catch (UnsupportedEncodingException e) {
                System.out.println("Error in reading file " + inputPath + ": " + e.getMessage());
                return;
            }
             //Declare the minimum support threshold for Eclat algorithm
            double minSupport=0.1;
            //Construct and run the Eclat algorithm
            AlgoEclat algo=new AlgoEclat();
            //Parameters: input file path, output file path, minimum support, and whether to show ID lists (set to false for this case)
            algo.runAlgorithm(outputPath, database, minSupport, false);
            //Print the statistics of the algorithm execution
            algo.printStats();
            System.out.println("---Eclat algorithm result exported to:"+outputPath+"---");
        }catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
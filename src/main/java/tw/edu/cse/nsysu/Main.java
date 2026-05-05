package tw.edu.cse.nsysu;

import java.sql.*;
import java.io.File;

public class Main{
    public static void main(String[] args){
        //Functionality buttons
        boolean importData=false;
        boolean featureExtract=false;
        boolean toSPMF=false;
        boolean runEclat=false;
        boolean runEclatFPgrowth=false;
        boolean runFPGrowth=false;
        //Database connection string for SQLite database
        String db="jdbc:sqlite:db/SQLIA.db";
        Connection MyConn=null;
        //Declare the output file name and directory for SPMF input .txt file
        String OutputDir="data/processed";
        String OutputFile="SPMF_input_data.txt";
        String OutputName=OutputDir+"/"+OutputFile;
        try{
            //Construct connection to SQLIA.db
            MyConn=DriverManager.getConnection(db);
            System.out.println("--Database connection success!--");
            if (importData){
                //Start import csv data into database table
                DataImporter importer=new DataImporter(MyConn);
                importer.Import();
                System.out.println("--Data Import Success!--");
            }
            if(featureExtract){
                //Do feature extraction
                FeatureExtract MyExtract=new FeatureExtract();
                ExtractProcess processor=new ExtractProcess(MyExtract, MyConn);
                processor.FillFeatureVector();
                System.out.println("--Feature extraction complete!--");
            }
            if(toSPMF){
                //Make sure the output direstory exists
                File directory = new File(OutputDir);
                if (!directory.exists()) {
                    if (directory.mkdirs()) {
                        System.out.println("-- Created directory: " + OutputDir + " --");
                    }
                }
                 //Transfer to SPMF input .txt file 
                ToTransactionList transaction=new ToTransactionList(MyConn);
                transaction.Export(OutputName);
            }
            //Run association rule mining algorithms
            if(runEclat){
                //1. Eclat
                //Declare the minimum support threshold for Eclat algorithm
                double minSupport=0.1;
                String eclatDir="data/processed";
                String eclatOutput=eclatDir+"/Eclat_output_minSup_"+minSupport+".txt";
                String eclatInput="data/processed/SPMF_input_data.txt";
                Eclat eclat=new Eclat();
                eclat.EclatRunner(eclatInput, eclatOutput, minSupport);
            }
            if(runEclatFPgrowth){
                //2. FP-Growth
                //Declare the minimum support threshold for FP-Growth algorithm
                double minSupport=0.05; // Assuming this is the minimum support threshold (e.g., 10%)
                double minConf=0.8; // Assuming this is the minimum confidence threshold
                String fpgrowthDir="data/processed";
                String fpgrowthOutput=fpgrowthDir+"/Eclat_FPGrowth_output_minSup_"+minSupport+"_minConf_"+minConf+".txt";
                String fpgrowthInput="data/processed/SPMF_input_data.txt";
                Eclat_FPGrowth eclat_fpgrowth=new Eclat_FPGrowth();
                eclat_fpgrowth.runEclat_FPGrowth(fpgrowthInput, fpgrowthOutput, minSupport, minConf);
            }
            if(runFPGrowth){
                //3. FP-Growth
                //Declare the minimum support threshold for FP-Growth algorithm
                double minSupport=0.05; // Assuming this is the minimum support threshold (e.g., 10%)
                double minConf=0.8; // Assuming this is the minimum confidence threshold
                String fpgrowthDir="data/processed";
                String fpgrowthOutput=fpgrowthDir+"/FPGrowth_output_minSup_"+minSupport+"_minConf_"+minConf+".txt";
                String fpgrowthInput="data/processed/SPMF_input_data.txt";
                FPGrowth fpgrowth=new FPGrowth();
                fpgrowth.runFPGrowth(fpgrowthInput, fpgrowthOutput, minSupport, minConf);
            }
        }catch(SQLException e){
            System.err.println("Connection to database fail: "+e.getMessage());
            e.printStackTrace();
        }catch(Exception e){
            System.err.println("Unexpected error happens: "+e.getMessage());
            e.printStackTrace();
        }finally{
            try{
                //Close the database connection
                if(MyConn!=null){
                    MyConn.close();
                    System.out.println("--Connection to database closed!--");
                }
            }catch(SQLException ex){
                ex.printStackTrace();
            }
        }
    }
}
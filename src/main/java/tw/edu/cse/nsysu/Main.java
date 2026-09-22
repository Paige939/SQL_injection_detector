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
        boolean runModel=false;
        boolean resetIsTrained=true;
        boolean runIncremental=true;
        //folder button
        int stageNumber; //Stage number to distinguish stage 3,4,5
        int versionNumber; //1: original version 2: weaker minConf 3: More features        //Database connection string for SQLite database
        String db="jdbc:sqlite:db/SQLIA.db";
        Connection MyConn=null;
        //Declare the output file name and directory for SPMF input .txt file
        String OutputDir;
        //The output directory depends on stage and version number
        //Here set the Stage number and version number
        stageNumber=4; 
        versionNumber=1;
        if(stageNumber==3){
            if(versionNumber==1)
                OutputDir="data/processed/Stage3/Version1";
            else if(versionNumber==2)
                OutputDir="data/processed/Stage3/Version2";
            else if(versionNumber==3)
                OutputDir="data/processed/Stage3/Version3";
            else {     
                System.err.println("Invalid version number! Put into default directory");
                OutputDir="data/processed";
            }
        }else if(stageNumber==4){
            if(versionNumber==1)
                OutputDir="data/processed/Stage4/Version1";
            else if(versionNumber==2)
                OutputDir="data/processed/Stage4/Version2";
            else if(versionNumber==3)
                OutputDir="data/processed/Stage4/Version3";
            else {     
                System.err.println("Invalid version number! Put into default directory");
                OutputDir="data/processed";
            }
        }else if(stageNumber==5){
            if(versionNumber==1)
                OutputDir="data/processed/Stage5/Version1";
            else if(versionNumber==2)
                OutputDir="data/processed/Stage5/Version2";
            else if(versionNumber==3)
                OutputDir="data/processed/Stage5/Version3";
            else {     
                System.err.println("Invalid version number! Put into default directory");
                OutputDir="data/processed";
            }
        }else{
            System.err.println("Invalid stage number! Put into default directory");
            OutputDir="data/processed"; //Default output directory
        }

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
                transaction.Export(OutputName, stageNumber);
            }
            //Run association rule mining algorithms
            if(runEclat){
                //1. Eclat
                //Declare the minimum support threshold for Eclat algorithm
                double minSupport=0.05;
                String eclatDir=OutputDir;
                String eclatOutput=eclatDir+"/Eclat_output_minSup_"+minSupport+".txt";
                String eclatInput=OutputName;
                Eclat eclat=new Eclat();
                eclat.EclatRunner(eclatInput, eclatOutput, minSupport);
            }
            if(runEclatFPgrowth){
                //2. FP-Growth
                //Declare the minimum support threshold for FP-Growth algorithm
                double minSupport=0.1; // Assuming this is the minimum support threshold (e.g., 10%)
                double minConf=0.6; // Assuming this is the minimum confidence threshold
                String fpgrowthDir=OutputDir;
                String fpgrowthOutput=fpgrowthDir+"/Eclat_FPGrowth_output_minSup_"+minSupport+"_minConf_"+minConf+".txt";
                String fpgrowthInput=OutputName;
                Eclat_FPGrowth eclat_fpgrowth=new Eclat_FPGrowth();
                eclat_fpgrowth.runEclat_FPGrowth(fpgrowthInput, fpgrowthOutput, minSupport, minConf);
            }
            if(runFPGrowth){
                //3. FP-Growth
                //Declare the minimum support threshold for FP-Growth algorithm
                double minSupport=0.1; // Assuming this is the minimum support threshold (e.g., 10%)
                double minConf=0.6; // Assuming this is the minimum confidence threshold
                String fpgrowthDir=OutputDir;
                String fpgrowthOutput=fpgrowthDir+"/FPGrowth_output_minSup_"+minSupport+"_minConf_"+minConf+".txt";
                String fpgrowthInput=OutputName;
                FPGrowth fpgrowth=new FPGrowth();
                fpgrowth.runFPGrowth(fpgrowthInput, fpgrowthOutput, minSupport, minConf);
            }
            if(runModel){
                System.out.println("\n=== Base Model Training===");
                MLTrainer trainer=new MLTrainer(MyConn);
                trainer.ModelRunner();
            }
            if(resetIsTrained){
                System.out.println("\n=== Reset isTrained Flag===");
                ResetIsTrained resetter=new ResetIsTrained(MyConn);
                resetter.resetIsTrained();
                System.out.println("--Reset isTrained complete!--");
            }
            if(runIncremental){
                System.out.println("\n=== Incremental Learning===");
                IncrementalRunner incrementalRunner=new IncrementalRunner(MyConn);
                incrementalRunner.run(2000, 256, 15); //warmupSize=2000, batchSize=256, ensembleSize=15
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
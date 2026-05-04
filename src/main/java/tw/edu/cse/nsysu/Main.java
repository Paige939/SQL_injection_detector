package tw.edu.cse.nsysu;

import java.sql.*;
import java.io.File;

public class Main{
    public static void main(String[] args){
        //Functionality buttons
        boolean importData=false;
        boolean featureExtract=false;
        boolean toSPMF=false;
        boolean runEclat=true;
        boolean runFPgrowth=false;
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
                double minSupport=0.05;
                String eclatDir="data/processed";
                String eclatOutput=eclatDir+"/Eclat_output_minSup_"+minSupport+".txt";
                Eclat eclat=new Eclat();
                eclat.EclatRunner(OutputName, eclatOutput, minSupport);
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
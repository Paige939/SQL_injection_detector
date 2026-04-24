package tw.edu.cse.nsysu;

import java.sql.*;

public class Main{
    public static void main(String[] args){
        String db="jdbc:sqlite:db/SQLIA.db";
        Connection MyConn=null;
        try{
            //Construct connection to SQLIA.db
            MyConn=DriverManager.getConnection(db);
            System.out.println("--Database connection success!--");
            //Start import csv data into database table
            DataImporter importer=new DataImporter(MyConn);
            importer.Import();
            System.out.println("--Data Import Success!--");
            //Do feature extraction
            FeatureExtract MyExtract=new FeatureExtract();
            ExtractProcess processor=new ExtractProcess(MyExtract, MyConn);
            processor.FillFeatureVector();
            System.out.println("--Feature extraction complete!--");
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
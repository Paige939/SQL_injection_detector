package tw.edu.cse.nsysu;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import java.sql.*;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.File;

public class DataImporter{
    private static final String schemaPath="SQLIA_schema.sql";
    private static final String file="data/raw/Modified_SQL_Dataset.csv";
    private Connection conn;
    public DataImporter(Connection conn){
        this.conn=conn;
    }
    public void Import(){
        //Ensure that folder named "db" exists
        File dbDir=new File("db");
        if(!dbDir.exists()){  //If not exist, create one
            dbDir.mkdirs();
        }
        //sql query to insert raw_sql, label, feature_vector and is_trained columns
        String sql="INSERT OR IGNORE INTO training_data (raw_sql, label, feature_vector, is_trained) VALUES (?, ?, ?, 0)";
        //Construct and Initialize the database table by executing SQLIA_schema.sql
        System.out.println("Executing SQLIA_schema.sql to construct database...");
        try{
            String setupSQL=new String(Files.readAllBytes(Paths.get(schemaPath)), StandardCharsets.UTF_8);
            try(Statement stmt=conn.createStatement()){
                for(String singleSql : setupSQL.split(";")){
                    if(!singleSql.trim().isEmpty()){
                        stmt.execute(singleSql);
                    }
                }
            }
            System.out.println("Database Construct Seccessfully!");
        }catch(IOException | SQLException e){
            System.err.println("Db Construct fail: "+e.getMessage());
            return;
        }
        try{
            //Close the auto-commit function which is default in sqlite, instead, use hand transaction
            conn.setAutoCommit(false);
            //Start to import data from .csv file
            try(FileReader reader=new FileReader(file, StandardCharsets.UTF_8); //Use UTF-8 to avoid garbled characters
            CSVParser parser=new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader()); //First line is a header)
            PreparedStatement pstmt=conn.prepareStatement(sql)){
                int count=0;
                int batch=1000;  //Do db insertion action every 1000 batch to accelerate speed
                for(CSVRecord record : parser){
                    try{
                        String rawsql=record.get("Query");
                        String label=record.get("Label");
                        pstmt.setString(1, rawsql);
                        pstmt.setInt(2, Integer.parseInt(label));
                        pstmt.setString(3, "");  //Set feature vector with default null string
                        pstmt.addBatch(); //add this instruction into later-to -do list
                        count++;
                        //Write in action for every 1000 batch size of data
                        if(count%batch==0){
                            pstmt.executeBatch();
                        }
                    }catch(Exception e){  //If a certain line is not readable or has error, just skip it!
                        System.err.println("Skip the line with error: "+e.getMessage());
                    }
                }
                pstmt.executeBatch(); //Handle the rest of data
                conn.commit();
                System.out.println("Data Import Complete! There are "+count+" numbers of data.");
            }catch(SQLException e){
                if(conn!=null){
                    try{
                        conn.rollback();
                    }catch(SQLException ex){
                        ex.printStackTrace();
                    }
                }
                System.err.println("SQL execution error: "+e.getMessage());
            }finally{
                try{
                    conn.setAutoCommit(true);  //Turn auto commit on
                }catch(SQLException e){
                    e.printStackTrace();
                }
            }
        }catch(SQLException e){
            System.err.println("Database connection status error: "+e.getMessage());
        }catch(IOException e) {
            System.err.println("File reading error: " + e.getMessage());
        }  
    }
}

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.HashSet;
import java.util.Set;
import tw.edu.cse.nsysu.preprocess.DatasetPreprocessor;
import tw.edu.cse.nsysu.preprocess.RawRecord;

public class DataImporter{
    private static final String schemaPath="SQLIA_schema.sql";
    private static final int STREAM_BATCH_SIZE=256;
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
        //Write the cleaned record and its explicit evaluation/stream metadata.
        String sql="INSERT INTO training_data "
            + "(raw_sql, label, feature_vector, source, split_group, batch_seq, is_trained) "
            + "VALUES (?, ?, ?, ?, ?, ?, 0) "
                + "ON CONFLICT(source, raw_sql) DO UPDATE SET "
                + "label=excluded.label, "
            + "split_group=excluded.split_group, batch_seq=excluded.batch_seq";
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
            //Prepare all datasets before opening the insert statement.
            DatasetPreprocessor.PreparedData prepared = DatasetPreprocessor.prepare();
            List<RawRecord> records = new ArrayList<>(prepared.records());
            Collections.shuffle(records, new Random(42L));
            Set<RawRecord> trainRecords = new HashSet<>(prepared.stratified().train());
            Set<RawRecord> validationRecords = new HashSet<>(prepared.stratified().val());
            try(PreparedStatement pstmt=conn.prepareStatement(sql)){
                int count=0;
                int streamIndex=0;
                int batch=1000;  //Do db insertion action every 1000 batch to accelerate speed
                for(RawRecord record : records){
                    try{
                        String rawsql=record.rawText;
                        int label=Integer.parseInt(record.rawLabel.trim());
                        int batchSeq=(streamIndex / STREAM_BATCH_SIZE) + 1;
                        pstmt.setString(1, rawsql);
                        pstmt.setInt(2, label);
                        pstmt.setString(3, "");  //Set feature vector with default null string
                        pstmt.setString(4, record.source);
                        pstmt.setString(5, trainRecords.contains(record) ? "train"
                                : validationRecords.contains(record) ? "validation" : "test");
                        pstmt.setInt(6, batchSeq);
                        pstmt.addBatch(); //add this instruction into later-to -do list
                        count++;
                        streamIndex++;
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

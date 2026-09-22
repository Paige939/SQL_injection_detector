package tw.edu.cse.nsysu;

import java.sql.*;
import java.util.Arrays;

public class ExtractProcess{
    private FeatureExtract extractor;
    private Connection conn;
    public ExtractProcess(FeatureExtract extractor, Connection conn){
        this.extractor=extractor;
        this.conn=conn;
    }
    public void FillFeatureVector(){
        //Select the data with null string in the column of feature vector
        String selectSql="SELECT id, raw_sql FROM training_data WHERE feature_vector=''";
        //Update the feature_vector column, fill in the vector values
        String updateSql="UPDATE training_data SET feature_vector=? WHERE id=?";
        try(Statement stmt=conn.createStatement(); ResultSet r=stmt.executeQuery(selectSql); PreparedStatement pstmt=conn.prepareStatement(updateSql)){
            conn.setAutoCommit(false);  //Close the auto commit
            //Since there are more than 30000 data, we'll update the column in batches
            int count=0;
            while(r.next()){
                //Using the selecting sql command to get id and raw_sql
                int id=r.getInt("id");
                String raw_query=r.getString("raw_sql");
                //Do the feature extraction
                double[] vector=extractor.extract(raw_query);
                //Store the result into the db table
                pstmt.setString(1, Arrays.toString(vector));
                pstmt.setInt(2, id);
                pstmt.addBatch();  //updating in batches
                count++;
                //Updating the feature_vector column every 1000 piece of data
                if(count%1000==0){
                    pstmt.executeBatch();
                    conn.commit();
                }
            }
            //Handle the rest data by turning on the auto commit
            pstmt.executeBatch();
            conn.commit();
            conn.setAutoCommit(true);
            System.out.println("All feature_vector update complete! There are "+count+" pieces of data.");
        }catch(SQLException e){
            try{
                conn.rollback();
            }catch(SQLException ex){
                ex.printStackTrace();
            }
        }
    }
}
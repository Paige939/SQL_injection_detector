package tw.edu.cse.nsysu;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.sql.*;
import java.util.Arrays;
import java.util.Set;

public class ToTransactionList{
    private Connection conn;
    public ToTransactionList(Connection conn){
        this.conn=conn;
    }
    public void Export(String OutputPath, int stageNumber){
        String query="SELECT feature_vector, label FROM training_data";
        try(Statement stmt=conn.createStatement(); ResultSet r=stmt.executeQuery(query); PrintWriter writer=new PrintWriter(OutputPath, "UTF-8")){
           int count=0;  //count total number of data needed to be writen into .txt file
            while(r.next()){
                //Get feature vector strings from training_data table and remove []
                String features=r.getString("feature_vector").replace("[","").replace("]","");
                String[] new_features=features.split(", ");
                //Transfer to a double vector
                double[] new_vector=Arrays.stream(new_features).mapToDouble(Double::parseDouble).toArray();
                //Get labels from table
                int label=r.getInt("label");
                //Convert the six-dimensional vector into categorical ARM items.
                StringBuilder transaction=new StringBuilder();
                Set<Integer> items = FeatureForML.toTransactionSet(new_vector);
                items.stream().sorted().forEach(item -> transaction.append(item).append(' '));
                if(stageNumber==3){
                    //Append 100 or 101 to the transaction list according to the labels
                    transaction.append(label==1?"101 ": "100 "); //If label=1(malicious), list will contain 101; otherwise, list will contain 100
                }
                writer.println(transaction.toString());
                count++;
            }
            System.out.println("There are "+count+" numbers of data beem export to: "+OutputPath);
        }catch(SQLException | IOException e){
            e.printStackTrace();
        }
    }
}


package tw.edu.cse.nsysu;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.sql.*;
import java.util.Arrays;

public class ToTransactionList{
    //Declare the thresholds
    public static final double LONG_LEN=100.0;
    public static final double EQ_THRES=3.0;
    public static final double WHERE_THRES=1.0;
    public static final double SELECT_THRES=1.0;
    public static final double UNION_THRES=1.0;
    public static final double QUOTE_THRES=2.0;
    public static final double COMMENT_THRES=0.0;
    public static final double OR_ACCONT=0.2;

    private Connection conn;
    public ToTransactionList(Connection conn){
        this.conn=conn;
    }
    public void Export(String OutputPath){
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
                //Construct a transaction list for each SQL query
                StringBuilder transaction=new StringBuilder();
                //Using thresholds to determine what the transaction list will be look like
                if(new_vector[0]>LONG_LEN)  //When query length>100, there will be a 1 in the list
                    transaction.append("1 ");
                if(new_vector[1]>EQ_THRES)  //When query equal-sign number>3, there will be a 2 in the list
                    transaction.append("2 ");
                if(new_vector[2]==WHERE_THRES) //When query WHERE number =1, there will be a 3 in the list
                    transaction.append("3 ");
                else if(new_vector[2]>WHERE_THRES) //If query WHERE number>1, there will be a 33 in the list, means high risk of attack happen
                    transaction.append("33 ");
                if(new_vector[3]==SELECT_THRES) //When query SELECT number=1, there will be a 4 in the list
                    transaction.append("4 ");
                else if(new_vector[3]>SELECT_THRES) //If query SELECT number>1, there will be a 44 in the list, means high risk of attack happen
                    transaction.append("44 ");
                if(new_vector[4]==UNION_THRES) //When query UNION number=1, there will be a 5 in the list
                    transaction.append("5 ");
                else if(new_vector[4]>UNION_THRES) //If query UNION number>1, there will be a 55 in the list, means high risk of attack happen
                    transaction.append("55 ");
                if(new_vector[5]>QUOTE_THRES) //When query single quote number>2, there will be a 6 in the list
                    transaction.append("6 ");
                if(new_vector[6]>COMMENT_THRES) //When comment symbol number>0, there will be a 7 in the list
                    transaction.append("7 ");
                if(new_vector[7]>OR_ACCONT) //When query or ratio>0.2, there will be a 8 in the list
                    transaction.append("8 ");
                //Append 100 or 101 to the transaction list according to the labels
                transaction.append(label==1?"101 ": "100 "); //If label=1(malicious), list will contain 101; otherwise, list will contain 100
                writer.println(transaction.toString());
                count++;
            }
            System.out.println("There are "+count+" numbers of data beem export to: "+OutputPath);
        }catch(SQLException | IOException e){
            e.printStackTrace();
        }
    }
}


package tw.edu.cse.nsysu;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class ResetIsTrained{
    private final Connection conn; //Database connection
    public ResetIsTrained(Connection conn){
        this.conn = conn;
    }
    public void resetIsTrained() throws SQLException{
        String sql = "UPDATE training_data SET is_trained = 0"; //SQL query
        try(PreparedStatement pstmt = conn.prepareStatement(sql)){
            pstmt.executeUpdate(); //Execute the update query
        }catch(SQLException e){
            System.err.println("Failed to reset isTrained: " + e.getMessage());
            throw e; //Rethrow the exception for further handling
        }
    }
}
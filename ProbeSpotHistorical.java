import java.sql.*;
public class ProbeSpotHistorical {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:postgresql://localhost:8812/qdb";
    try (Connection c = DriverManager.getConnection(url, "admin", "quest");
         PreparedStatement ps = c.prepareStatement("select underlying, max(trade_date) as max_trade_date from spot_historical where underlying in ('NIFTY','BANKNIFTY') group by underlying order by underlying");
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        System.out.println(rs.getString(1) + " " + rs.getDate(2));
      }
    }
  }
}

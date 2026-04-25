import java.sql.*;
public class Probe {
  public static void main(String[] args) throws Exception {
    Class.forName("org.postgresql.Driver");
    try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:8812/qdb", "admin", "quest")) {
      String[] queries = {
        "select 'spot_live' as table_name, count(*) as total_count, max(exchange_ts) as last_ts from spot_live",
        "select 'options_live' as table_name, count(*) as total_count, max(exchange_ts) as last_ts from options_live",
        "select 'options_live_enriched' as table_name, count(*) as total_count, max(exchange_ts) as last_ts from options_live_enriched",
        "select 'options_live_15m' as table_name, count(*) as total_count, max(last_updated_ts) as last_ts from options_live_15m",
        "select 'live_structure_snapshot' as table_name, count(*) as total_count, max(snapshot_ts) as last_ts from live_structure_snapshot"
      };
      for (String q : queries) {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(q)) {
          while (rs.next()) {
            System.out.println(rs.getString(1) + "|" + rs.getLong(2) + "|" + rs.getTimestamp(3));
          }
        }
      }
      System.out.println("-- recent 30s --");
      String[] recent = {
        "select 'spot_live', count(*) from spot_live where exchange_ts >= dateadd('s', -30, now())",
        "select 'options_live', count(*) from options_live where exchange_ts >= dateadd('s', -30, now())",
        "select 'options_live_enriched', count(*) from options_live_enriched where exchange_ts >= dateadd('s', -30, now())",
        "select 'options_live_15m', count(*) from options_live_15m where last_updated_ts >= dateadd('s', -30, now())",
        "select 'live_structure_snapshot', count(*) from live_structure_snapshot where snapshot_ts >= dateadd('s', -30, now())"
      };
      for (String q : recent) {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(q)) {
          while (rs.next()) {
            System.out.println(rs.getString(1) + "|" + rs.getLong(2));
          }
        }
      }
    }
  }
}

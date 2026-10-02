package dev.heimdall.results;

import com.zaxxer.hikari.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

public final class Database {
  private Database() {}

  public static HikariDataSource connect(
      String url, String username, String password, int poolSize) {
    var c = new HikariConfig();
    c.setJdbcUrl(url);
    c.setUsername(username);
    c.setPassword(password);
    c.setMaximumPoolSize(poolSize);
    c.setConnectionTimeout(5000);
    c.setPoolName("heimdall-%s".formatted(java.util.UUID.randomUUID()));
    return new HikariDataSource(c);
  }

  public static HikariDataSource connect() {
    return connect(
        System.getenv().getOrDefault("JDBC_URL", "jdbc:postgresql://localhost:5432/heimdall"),
        System.getenv().getOrDefault("DB_USER", "heimdall"),
        System.getenv().getOrDefault("DB_PASSWORD", "heimdall-local"),
        8);
  }

  public static void migrate(DataSource dataSource) {
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
  }
}

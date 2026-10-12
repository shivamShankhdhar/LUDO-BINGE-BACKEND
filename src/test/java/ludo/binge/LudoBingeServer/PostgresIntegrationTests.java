package ludo.binge.LudoBingeServer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real PostgreSQL check. Creates/drops ONLY a random test-owned schema. */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_CONFIG", matches = ".+")
class PostgresIntegrationTests {
    @Test
    void migrationsAreRepeatableAndTransactionsAreAtomic() throws Exception {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(System.getenv("POSTGRES_TEST_CONFIG")))) {
            properties.load(reader);
        }
        String url = properties.getProperty("spring.datasource.url");
        String user = properties.getProperty("spring.datasource.username");
        String password = properties.getProperty("spring.datasource.password");
        String schema = "ludo_test_" + UUID.randomUUID().toString().replace("-", "");
        DriverManager.setLoginTimeout(10);
        try (var admin = DriverManager.getConnection(url, user, password)) {
            try {
                var flyway = Flyway.configure().dataSource(url, user, password)
                    .defaultSchema(schema).schemas(schema).locations("classpath:db/migration")
                    .cleanDisabled(true).validateMigrationNaming(true).load();
                assertEquals(2, flyway.migrate().migrationsExecuted);
                assertEquals(0, flyway.migrate().migrationsExecuted);
                flyway.validate();

                var config = new HikariConfig();
                config.setJdbcUrl(url);
                config.setUsername(user);
                config.setPassword(password);
                config.setSchema(schema);
                config.setMaximumPoolSize(2);
                config.setMinimumIdle(0);
                config.setConnectionTimeout(5000);
                config.setConnectionInitSql("SET statement_timeout = '15s'");
                try (var pool = new HikariDataSource(config)) {
                    var jdbc = new JdbcTemplate(pool);
                    assertEquals(schema, jdbc.queryForObject("SELECT current_schema()", String.class));
                    // This probe exists only inside the random test namespace.
                    jdbc.execute("CREATE TABLE transaction_probe (id integer PRIMARY KEY, value integer CHECK (value >= 0))");
                    var tx = new TransactionTemplate(new JdbcTransactionManager(pool));
                    assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
                        jdbc.update("INSERT INTO transaction_probe VALUES (?, ?)", 1, 10);
                        jdbc.update("INSERT INTO transaction_probe VALUES (?, ?)", 2, -1);
                    }));
                    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM transaction_probe", Integer.class));
                    tx.executeWithoutResult(status -> jdbc.update("INSERT INTO transaction_probe VALUES (?, ?)", 3, 20));
                    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM transaction_probe", Integer.class));
                }
            } finally {
                // schema is generated internally from a UUID, never supplied by a caller.
                try (var statement = admin.createStatement()) {
                    statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            }
        }
    }
}

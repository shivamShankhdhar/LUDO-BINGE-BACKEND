package ludo.binge.LudoBingeServer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class SchemaInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);
    private final JdbcTemplate jdbc;

    public SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Boolean exists = jdbc.queryForObject(
                "SELECT to_regclass('rate_limits') IS NOT NULL",
                Boolean.class
            );
            if (exists == null || !exists) {
                log.info("Schema tables missing in search_path. Executing V2 migration...");
                var resource = new ClassPathResource("db/migration/V2__online_backend.sql");
                String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                jdbc.execute(sql);
                log.info("Schema tables successfully initialized!");
            } else {
                log.info("Schema verified: rate_limits table exists.");
            }
        } catch (Exception e) {
            log.error("Failed to verify or initialize database schema", e);
        }
    }
}

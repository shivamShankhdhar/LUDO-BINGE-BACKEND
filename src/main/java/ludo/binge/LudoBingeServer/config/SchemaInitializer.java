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
            // First drop any incompatible legacy users table if id is not uuid
            jdbc.execute(
                "DO $$\n" +
                "BEGIN\n" +
                "  IF EXISTS (\n" +
                "    SELECT 1 FROM information_schema.columns \n" +
                "    WHERE table_name = 'users' AND column_name = 'id' AND udt_name <> 'uuid'\n" +
                "  ) THEN\n" +
                "    DROP TABLE users CASCADE;\n" +
                "  END IF;\n" +
                "END $$;"
            );

            Boolean exists = jdbc.queryForObject(
                "SELECT to_regclass('rate_limits') IS NOT NULL",
                Boolean.class
            );
            if (exists == null || !exists) {
                log.info("Schema tables missing in search_path. Executing V3 schema creation...");
                var resource = new ClassPathResource("db/migration/V3__ensure_schema.sql");
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

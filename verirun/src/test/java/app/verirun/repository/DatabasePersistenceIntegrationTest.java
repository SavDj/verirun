package app.verirun.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgreSqlTestcontainersConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class DatabasePersistenceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Test
    void databaseInitialization_shouldRunFlywayAndValidateSchemaWithHibernate() {
        Long flywayHistoryTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'",
                Long.class
        );
        Long successfulMigrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE AND type = 'SQL'",
                Long.class
        );
        Long simulationJobsTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'simulation_jobs'",
                Long.class
        );

        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                .isEqualTo("validate");
        assertThat(flywayHistoryTableCount).isEqualTo(1L);
        assertThat(successfulMigrationCount).isGreaterThan(0L);
        assertThat(simulationJobsTableCount).isEqualTo(1L);
    }
}

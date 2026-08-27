package app.verirun.repository;

import app.verirun.dto.SimulationRequest;
import app.verirun.entity.User;
import app.verirun.service.JobQueueService;
import app.verirun.service.SimulationJobPersistenceService;
import app.verirun.service.SimulationSubmissionService;
import app.verirun.service.VerilogSanitizerService;
import app.verirun.storage.SimulationStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgreSqlTestcontainersConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class DatabasePersistenceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SimulationJobRepository jobRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Test
    void databaseInitialization_shouldRunFlywayAndValidateFinalSchemaWithHibernate() {
        Long flywayHistoryTableCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.tables " + "WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'", Long.class);
        Long directoryPathCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.columns " + "WHERE table_schema = 'public' AND table_name = 'simulation_jobs' " + "AND column_name = 'directory_path'", Long.class);

        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(flywayHistoryTableCount).isEqualTo(1L);
        assertThat(directoryPathCount).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void submission_shouldExposeCommittedPendingJobBeforeQueuePublication() {
        User owner = new User("submission-visibility-" + UUID.randomUUID() + "@verirun.com");
        owner.setPasswordHash("password");
        UUID userId = userRepository.saveAndFlush(owner).getId();

        SimulationStorageService storageService = mock(SimulationStorageService.class);
        VerilogSanitizerService sanitizer = mock(VerilogSanitizerService.class);
        JobQueueService queueService = mock(JobQueueService.class);
        SimulationSubmissionService submissionService = new SimulationSubmissionService(userRepository, sanitizer, storageService, new SimulationJobPersistenceService(jobRepository, transactionManager), queueService);
        when(queueService.enqueueJob(anyString())).thenAnswer(invocation -> {
            String jobId = invocation.getArgument(0);
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT status, testbench_expected FROM simulation_jobs WHERE job_id = ?")) {
                statement.setString(1, jobId);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("status")).isEqualTo("PENDING");
                    assertThat(result.getBoolean("testbench_expected")).isTrue();
                }
            }
            return JobQueueService.PublicationResult.CONFIRMED;
        });

        String jobId = submissionService.submitSimulation(new SimulationRequest("module design; endmodule", "module tb; endmodule", null), userId);

        assertThat(jobRepository.findByJobId(jobId)).isPresent();
    }
}

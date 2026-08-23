package app.verirun.repository;

import app.verirun.entity.SimulationJob;
import app.verirun.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgreSqlTestcontainersConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class SimulationJobRepositoryIntegrationTest {

    private static final Instant CUTOFF = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private SimulationJobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findByJobId_shouldReloadPersistedOwnerAndSupportOwnerScopedLookups() {
        User owner = persistUser("job-owner@verirun.com");
        User otherUser = persistUser("other-owner@verirun.com");
        SimulationJob job = persistJob("job-owner-lookup", owner);

        entityManager.clear();

        SimulationJob reloaded =
                jobRepository.findByJobId(job.getJobId()).orElseThrow();

        assertThat(reloaded.getId()).isEqualTo(job.getId());
        assertThat(reloaded.getOwner().getId()).isEqualTo(owner.getId());

        SimulationJob ownerScoped =
                jobRepository.findByJobIdAndOwner_Id(job.getJobId(), owner.getId())
                        .orElseThrow();

        assertThat(ownerScoped.getId()).isEqualTo(job.getId());

        assertThat(jobRepository.findByJobIdAndOwner_Id(job.getJobId(), otherUser.getId()))
                .isEmpty();

        assertThat(jobRepository.existsByJobIdAndOwner_Id(job.getJobId(), owner.getId()))
                .isTrue();

        assertThat(jobRepository.existsByJobIdAndOwner_Id(job.getJobId(), otherUser.getId()))
                .isFalse();
    }

    @Test
    void findStuckJobs_shouldReturnOnlyRunningJobsAtOrBeforeCutoff() {
        User owner = persistUser("stuck-job-owner@verirun.com");

        persistJobWithStatus(
                "stuck-before",
                owner,
                SimulationJob.JobStatus.RUNNING,
                CUTOFF.minusSeconds(1)
        );
        persistJobWithStatus(
                "stuck-at-cutoff",
                owner,
                SimulationJob.JobStatus.RUNNING,
                CUTOFF
        );
        persistJobWithStatus(
                "stuck-after",
                owner,
                SimulationJob.JobStatus.RUNNING,
                CUTOFF.plusSeconds(1)
        );
        persistJobWithStatus(
                "pending-before",
                owner,
                SimulationJob.JobStatus.PENDING,
                CUTOFF.minusSeconds(1)
        );
        persistJobWithStatus(
                "completed-before",
                owner,
                SimulationJob.JobStatus.COMPLETED,
                CUTOFF.minusSeconds(1)
        );
        persistJobWithStatus(
                "failed-before",
                owner,
                SimulationJob.JobStatus.FAILED,
                CUTOFF.minusSeconds(1)
        );
        persistJobWithStatus(
                "running-without-start",
                owner,
                SimulationJob.JobStatus.RUNNING,
                null
        );

        assertThat(jobRepository.findStuckJobs(CUTOFF))
                .extracting(SimulationJob::getJobId)
                .containsExactlyInAnyOrder("stuck-before", "stuck-at-cutoff");
    }

    @Test
    void save_shouldRejectDuplicateJobId() {
        User firstOwner = persistUser("duplicate-job-first-owner@verirun.com");
        User secondOwner = persistUser("duplicate-job-second-owner@verirun.com");

        persistJob("duplicate-job-id", firstOwner);

        SimulationJob duplicate = newJob("duplicate-job-id", secondOwner);

        assertThatThrownBy(() -> jobRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insert_shouldRejectNullOwnerIdAtDatabaseLevel() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO simulation_jobs "
                        + "(id, job_id, owner_id, directory_path, created_at, status, retry_count) "
                        + "VALUES (?, ?, NULL, ?, ?, ?, ?)",
                UUID.randomUUID(),
                "missing-owner",
                "/tmp/missing-owner",
                Timestamp.from(CUTOFF),
                "PENDING",
                0
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insert_shouldRejectNonexistentOwnerAtDatabaseLevel() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO simulation_jobs "
                        + "(id, job_id, owner_id, directory_path, created_at, status, retry_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                "nonexistent-owner",
                UUID.randomUUID(),
                "/tmp/nonexistent-owner",
                Timestamp.from(CUTOFF),
                "PENDING",
                0
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insert_shouldRejectUnsupportedSimulationStatusAtDatabaseLevel() {
        User owner = persistUser("invalid-status-owner@verirun.com");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO simulation_jobs "
                        + "(id, job_id, owner_id, directory_path, created_at, status, retry_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                "invalid-status",
                owner.getId(),
                "/tmp/invalid-status",
                Timestamp.from(CUTOFF),
                "INVALID",
                0
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private User persistUser(String email) {
        User user = new User(email);
        user.setPasswordHash("password");
        return userRepository.saveAndFlush(user);
    }

    private SimulationJob persistJob(String jobId, User owner) {
        SimulationJob job = newJob(jobId, owner);
        return jobRepository.saveAndFlush(job);
    }

    private void persistJobWithStatus(String jobId, User owner, SimulationJob.JobStatus status,
                                      Instant startedAt) {
        SimulationJob job = newJob(jobId, owner);
        job.setStatus(status);
        job.setStartedAt(startedAt);
        jobRepository.saveAndFlush(job);
    }

    private SimulationJob newJob(String jobId, User owner) {
        return new SimulationJob(jobId, "/tmp/" + jobId, null, owner);
    }
}

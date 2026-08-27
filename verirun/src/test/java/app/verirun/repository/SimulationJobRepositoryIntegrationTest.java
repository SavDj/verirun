package app.verirun.repository;

import app.verirun.entity.SimulationJob;
import app.verirun.entity.User;
import app.verirun.dto.VerilatorOptions;
import app.verirun.dto.VerilatorOptions.BuildMode;
import app.verirun.dto.VerilatorOptions.OptimizationLevel;
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
import java.util.List;
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

        SimulationJob reloaded = jobRepository.findByJobId(job.getJobId()).orElseThrow();

        assertThat(reloaded.getId()).isEqualTo(job.getId());
        assertThat(reloaded.getOwner().getId()).isEqualTo(owner.getId());

        SimulationJob ownerScoped = jobRepository.findByJobIdAndOwner_Id(job.getJobId(), owner.getId()).orElseThrow();

        assertThat(ownerScoped.getId()).isEqualTo(job.getId());

        assertThat(jobRepository.findByJobIdAndOwner_Id(job.getJobId(), otherUser.getId())).isEmpty();

        assertThat(jobRepository.existsByJobIdAndOwner_Id(job.getJobId(), owner.getId())).isTrue();

        assertThat(jobRepository.existsByJobIdAndOwner_Id(job.getJobId(), otherUser.getId())).isFalse();
    }

    @Test
    void findStuckJobs_shouldReturnOnlyRunningJobsAtOrBeforeCutoff() {
        User owner = persistUser("stuck-job-owner@verirun.com");

        persistJobWithStatus("stuck-before", owner, SimulationJob.JobStatus.RUNNING, CUTOFF.minusSeconds(1));
        persistJobWithStatus("stuck-at-cutoff", owner, SimulationJob.JobStatus.RUNNING, CUTOFF);
        persistJobWithStatus("stuck-after", owner, SimulationJob.JobStatus.RUNNING, CUTOFF.plusSeconds(1));
        persistJobWithStatus("pending-before", owner, SimulationJob.JobStatus.PENDING, CUTOFF.minusSeconds(1));
        persistJobWithStatus("completed-before", owner, SimulationJob.JobStatus.COMPLETED, CUTOFF.minusSeconds(1));
        persistJobWithStatus("running-without-start", owner, SimulationJob.JobStatus.RUNNING, null);

        assertThat(jobRepository.findStuckJobs(CUTOFF)).extracting(SimulationJob::getJobId).containsExactlyInAnyOrder("stuck-before", "stuck-at-cutoff");
    }

    @Test
    void save_shouldRejectDuplicateJobId() {
        User firstOwner = persistUser("duplicate-job-first-owner@verirun.com");
        User secondOwner = persistUser("duplicate-job-second-owner@verirun.com");

        persistJob("duplicate-job-id", firstOwner);

        SimulationJob duplicate = newJob("duplicate-job-id", secondOwner);

        assertThatThrownBy(() -> jobRepository.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void testbenchExpectation_shouldRoundTripTrueAndFalse() {
        User owner = persistUser("testbench-expectation-owner@verirun.com");
        SimulationJob designOnly = new SimulationJob("design-only-expectation", false, null, owner);
        SimulationJob withTestbench = new SimulationJob("testbench-expectation", true, null, owner);
        jobRepository.saveAndFlush(designOnly);
        jobRepository.saveAndFlush(withTestbench);

        entityManager.clear();

        assertThat(jobRepository.findByJobId("design-only-expectation").orElseThrow().isTestbenchExpected()).isFalse();
        assertThat(jobRepository.findByJobId("testbench-expectation").orElseThrow().isTestbenchExpected()).isTrue();
    }

    @Test
    void insert_shouldRejectNullTestbenchExpectationAtDatabaseLevel() {
        User owner = persistUser("null-testbench-expectation-owner@verirun.com");

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO simulation_jobs " + "(id, job_id, owner_id, testbench_expected, created_at, status, retry_count) " + "VALUES (?, ?, ?, NULL, ?, ?, ?)", UUID.randomUUID(), "null-testbench-expectation", owner.getId(), Timestamp.from(CUTOFF), "PENDING", 0)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insert_shouldRejectNullOwnerIdAtDatabaseLevel() {
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO simulation_jobs " + "(id, job_id, owner_id, testbench_expected, created_at, status, retry_count) " + "VALUES (?, ?, NULL, ?, ?, ?, ?)", UUID.randomUUID(), "missing-owner", false, Timestamp.from(CUTOFF), "PENDING", 0)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insert_shouldRejectNonexistentOwnerAtDatabaseLevel() {
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO simulation_jobs " + "(id, job_id, owner_id, testbench_expected, created_at, status, retry_count) " + "VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), "nonexistent-owner", UUID.randomUUID(), false, Timestamp.from(CUTOFF), "PENDING", 0)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insert_shouldRejectUnsupportedSimulationStatusAtDatabaseLevel() {
        User owner = persistUser("invalid-status-owner@verirun.com");

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO simulation_jobs " + "(id, job_id, owner_id, testbench_expected, created_at, status, retry_count) " + "VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), "invalid-status", owner.getId(), false, Timestamp.from(CUTOFF), "INVALID", 0)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void claimJob_shouldTransitionPendingJobOnceAndRejectSecondClaim() {
        User owner = persistUser("claim-job-owner@verirun.com");
        SimulationJob job = persistJob("claim-once", owner);
        Instant firstClaim = Instant.parse("2026-01-02T03:04:05Z");
        Instant secondClaim = Instant.parse("2026-01-02T03:05:05Z");
        entityManager.flush();
        entityManager.clear();

        assertThat(jobRepository.claimJob(job.getJobId(), firstClaim)).isEqualTo(1);
        entityManager.clear();

        SimulationJob claimed = jobRepository.findByJobId(job.getJobId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(SimulationJob.JobStatus.RUNNING);
        assertThat(claimed.getStartedAt()).isEqualTo(firstClaim);

        assertThat(jobRepository.claimJob(job.getJobId(), secondClaim)).isZero();
        entityManager.clear();
        assertThat(jobRepository.findByJobId(job.getJobId()).orElseThrow().getStartedAt()).isEqualTo(firstClaim);
    }

    @Test
    void save_shouldRoundTripNonDefaultVerilatorOptions() {
        User owner = persistUser("options-owner@verirun.com");
        VerilatorOptions options = new VerilatorOptions(BuildMode.CC_MODEL, true, true, 7, true, true, 5, OptimizationLevel.O1,
                List.of("rtl", "vendor"), List.of("SYNTHESIS", "WIDTH=32"), List.of("WIDTH", "UNUSED"),
                List.of("--x-assign=fast"), List.of("+seed=42", "+verbose"));
        jobRepository.saveAndFlush(new SimulationJob("options-round-trip", true, options, owner));
        entityManager.clear();

        assertThat(jobRepository.findByJobId("options-round-trip").orElseThrow().getVerilatorOptions()).isEqualTo(options);
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

    private void persistJobWithStatus(String jobId, User owner, SimulationJob.JobStatus status, Instant startedAt) {
        SimulationJob job = newJob(jobId, owner);
        job.setStatus(status);
        job.setStartedAt(startedAt);
        jobRepository.saveAndFlush(job);
    }

    private SimulationJob newJob(String jobId, User owner) {
        return new SimulationJob(jobId, false, null, owner);
    }
}

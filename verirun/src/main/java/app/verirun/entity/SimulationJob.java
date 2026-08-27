package app.verirun.entity;

import app.verirun.dto.VerilatorOptions;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "simulation_jobs")
public class SimulationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String jobId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "testbench_expected", nullable = false)
    private boolean testbenchExpected;

    @Column(nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    private JobStatus status = JobStatus.PENDING;

    @Column(length = 1000)
    private String errorMessage;

    @Column(nullable = false)
    private Integer retryCount = 0;

    private Instant startedAt;

    private Instant completedAt;

    @Column(length = 10000)
    private String resultJson;

    @Convert(converter = VerilatorOptionsConverter.class)
    @Column(columnDefinition = "TEXT")
    private VerilatorOptions verilatorOptions;

    public SimulationJob() {
    }

    public SimulationJob(String jobId, boolean testbenchExpected, VerilatorOptions options, User owner) {
        this.jobId = jobId;
        this.owner = owner;
        this.testbenchExpected = testbenchExpected;
        this.verilatorOptions = options;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public User getOwner() {
        return owner;
    }

    public boolean isTestbenchExpected() {
        return testbenchExpected;
    }

    public void setTestbenchExpected(boolean testbenchExpected) {
        this.testbenchExpected = testbenchExpected;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public VerilatorOptions getVerilatorOptions() {
        return verilatorOptions != null ? verilatorOptions : VerilatorOptions.defaults();
    }

    public void setVerilatorOptions(VerilatorOptions verilatorOptions) {
        this.verilatorOptions = verilatorOptions;
    }

    public enum JobStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }
}

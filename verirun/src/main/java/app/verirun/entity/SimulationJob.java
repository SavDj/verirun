package app.verirun.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "simulation_jobs")
public class SimulationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String jobId;

    @Column(nullable = false)
    private String directoryPath;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant cleanupScheduledAt;
    private boolean cleanedUp = false;

    @Enumerated(EnumType.STRING)
    private JobStatus status = JobStatus.PENDING;

    @Column(length = 1000)
    private String errorMessage;

    public SimulationJob() {}

    public SimulationJob(String jobId, String directoryPath) {
        this.jobId = jobId;
        this.directoryPath = directoryPath;
        this.createdAt = Instant.now();
        this.cleanupScheduledAt = Instant.now().plusSeconds(3600); // 1 hour
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

    public String getDirectoryPath() {
        return directoryPath;
    }

    public void setDirectoryPath(String directoryPath) {
        this.directoryPath = directoryPath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCleanupScheduledAt() {
        return cleanupScheduledAt;
    }

    public void setCleanupScheduledAt(Instant cleanupScheduledAt) {
        this.cleanupScheduledAt = cleanupScheduledAt;
    }

    public boolean isCleanedUp() {
        return cleanedUp;
    }

    public void setCleanedUp(boolean cleanedUp) {
        this.cleanedUp = cleanedUp;
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

    public enum JobStatus {
        PENDING, COMPLETED, FAILED
    }
}
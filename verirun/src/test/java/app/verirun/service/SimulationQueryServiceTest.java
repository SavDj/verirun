package app.verirun.service;

import app.verirun.dto.JobStatusResponse;
import app.verirun.dto.VerilatorOptions;
import app.verirun.dto.VerilatorOptions.BuildMode;
import app.verirun.dto.VerilatorOptions.OptimizationLevel;
import app.verirun.entity.SimulationJob;
import app.verirun.entity.User;
import app.verirun.repository.SimulationJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationQueryServiceTest {

    @Mock
    private SimulationJobRepository jobRepository;

    private final UUID userId = UUID.randomUUID();
    private SimulationQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new SimulationQueryService(jobRepository);
    }

    @Test
    void getJobStatus_shouldMapCompletedOwnedJob() {
        User owner = new User("owner@verirun.com");
        owner.setId(userId);
        VerilatorOptions options = new VerilatorOptions(BuildMode.CC_MODEL, true, false, 4, true, true, 3, OptimizationLevel.O1,
                List.of("rtl"), List.of("SYNTHESIS"), List.of("WIDTH"), List.of("--x-assign=fast"), List.of("+seed=7"));
        SimulationJob job = new SimulationJob("job-123", false, options, owner);
        Instant startedAt = Instant.parse("2026-01-02T03:04:05Z");
        Instant completedAt = Instant.parse("2026-01-02T03:05:05Z");
        job.setStatus(SimulationJob.JobStatus.COMPLETED);
        job.setStartedAt(startedAt);
        job.setCompletedAt(completedAt);
        job.setResultJson("{\"passed\":true}");
        job.setRetryCount(1);

        when(jobRepository.findByJobIdAndOwner_Id("job-123", userId)).thenReturn(Optional.of(job));

        Optional<JobStatusResponse> result = queryService.getJobStatus("job-123", userId);

        assertThat(result).isPresent();
        JobStatusResponse response = result.orElseThrow();
        assertThat(response.jobId()).isEqualTo("job-123");
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.createdAt()).isEqualTo(job.getCreatedAt().toString());
        assertThat(response.startedAt()).isEqualTo(startedAt.toString());
        assertThat(response.completedAt()).isEqualTo(completedAt.toString());
        assertThat(response.errorMessage()).isNull();
        assertThat(response.retryCount()).isEqualTo(1);
        assertThat(response.result()).isEqualTo("{\"passed\":true}");
        assertThat(response.buildMode()).isEqualTo("CC_MODEL");

        verify(jobRepository).findByJobIdAndOwner_Id(eq("job-123"), eq(userId));
        verify(jobRepository, never()).findByJobId(anyString());
    }

    @Test
    void getJobStatus_shouldReturnEmptyWhenJobIsUnownedOrMissing() {
        when(jobRepository.findByJobIdAndOwner_Id("job-123", userId)).thenReturn(Optional.empty());

        Optional<JobStatusResponse> result = queryService.getJobStatus("job-123", userId);

        assertThat(result).isEmpty();
        verify(jobRepository).findByJobIdAndOwner_Id("job-123", userId);
        verify(jobRepository, never()).findByJobId(anyString());
    }

    @Test
    void getJobStatus_shouldMapFailedJobWithoutResultMetadata() {
        User owner = new User("failed-owner@verirun.com");
        owner.setId(userId);
        VerilatorOptions options = new VerilatorOptions(BuildMode.LINT_ONLY, null, null, null, null, null, null, null,
                null, null, null, null, null);
        SimulationJob job = new SimulationJob("failed-job", false, options, owner);
        Instant completedAt = Instant.parse("2026-01-02T03:05:05Z");
        job.setStatus(SimulationJob.JobStatus.FAILED);
        job.setCompletedAt(completedAt);
        job.setErrorMessage("Worker execution failed");
        job.setRetryCount(2);
        job.setResultJson("{\"passed\":true}");

        when(jobRepository.findByJobIdAndOwner_Id("failed-job", userId)).thenReturn(Optional.of(job));

        JobStatusResponse response = queryService.getJobStatus("failed-job", userId).orElseThrow();

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.completedAt()).isEqualTo(completedAt.toString());
        assertThat(response.errorMessage()).isEqualTo("Worker execution failed");
        assertThat(response.retryCount()).isEqualTo(2);
        assertThat(response.result()).isNull();
        assertThat(response.buildMode()).isNull();
    }
}

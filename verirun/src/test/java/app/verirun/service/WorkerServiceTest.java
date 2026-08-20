package app.verirun.service;

import app.verirun.docker.DockerExecutor;
import app.verirun.dto.JobMessage;
import app.verirun.entity.SimulationJob;
import app.verirun.entity.User;
import app.verirun.repository.SimulationJobRepository;
import app.verirun.storage.ArtifactStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerServiceTest {

    @Mock
    private SimulationJobRepository jobRepository;
    @Mock
    private DockerExecutor dockerExecutor;
    @Mock
    private VerilatorCommandBuilder commandBuilder;
    @Mock
    private VerilogParserService parserService;
    @Mock
    private ArtifactStorageService storageService;
    @Mock
    private JobQueueService jobQueueService;

    private WorkerService workerService;

    private User owner;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        owner = new User("worker-owner@verirun.com");
        owner.setId(UUID.randomUUID());
        workerService = new WorkerService(
                dockerExecutor,
                jobRepository,
                commandBuilder,
                parserService,
                storageService,
                jobQueueService,
                tempDir.toString(),
                120,
                60,
                536870912L,
                1L,
                100000,
                180,
                2
        );
    }

    @Test
    void recoverStuckJobs_shouldResetStatusAndEnqueue_WhenStuckJobsFound() {
        SimulationJob stuckJob = new SimulationJob("job-stuck", tempDir.resolve("job").toString(), null, owner);
        stuckJob.setStatus(SimulationJob.JobStatus.RUNNING);
        stuckJob.setStartedAt(Instant.now().minusSeconds(300));

        when(jobRepository.findStuckJobs(any(Instant.class))).thenReturn(List.of(stuckJob));

        workerService.recoverStuckJobs();

        assertThat(stuckJob.getStatus()).isEqualTo(SimulationJob.JobStatus.PENDING);
        assertThat(stuckJob.getStartedAt()).isNull();

        verify(jobRepository).save(stuckJob);
        verify(jobQueueService).enqueueJob("job-stuck");
    }

    @Test
    void processJob_shouldSetStatusToFailed_WhenMaxRetriesExceeded() {
        SimulationJob job = new SimulationJob("job-fail", tempDir.resolve("job-fail").toString(), null, owner);
        job.setStatus(SimulationJob.JobStatus.PENDING);
        job.setRetryCount(2);

        when(jobRepository.findByJobId("job-fail")).thenReturn(Optional.of(job));

        when(jobRepository.claimJob(eq("job-fail"), any(Instant.class))).thenReturn(1);

        when(dockerExecutor.runBuild(any(), any(), anyBoolean(), anyLong(), anyDouble(), anyInt(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("Docker execution failed"));

        assertThatThrownBy(() -> workerService.processJob(new JobMessage("job-fail")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Docker execution failed");

        assertThat(job.getStatus()).isEqualTo(SimulationJob.JobStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("Docker execution failed");
        assertThat(job.getCompletedAt()).isNotNull();

        verify(jobRepository).save(job);
        verify(jobRepository).findByJobId("job-fail");
    }
}

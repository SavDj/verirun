package app.verirun.service;

import app.verirun.docker.DockerExecutor;
import app.verirun.docker.DockerExecutor.ContainerResult;
import app.verirun.dto.JobMessage;
import app.verirun.dto.VerilatorOptions;
import app.verirun.dto.VerilatorOptions.BuildMode;
import app.verirun.dto.VerilatorOptions.OptimizationLevel;
import app.verirun.entity.SimulationJob;
import app.verirun.entity.User;
import app.verirun.exception.InputMaterializationException;
import app.verirun.repository.SimulationJobRepository;
import app.verirun.storage.SimulationStorageService;
import app.verirun.storage.SimulationStorageService.Output;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private SimulationStorageService storageService;

    @Mock
    private JobQueueService jobQueueService;

    @Mock
    private WorkerWorkspaceService workspaceService;

    @Mock
    private SimulationInputMaterializer inputMaterializer;

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
                workspaceService,
                inputMaterializer,
                120,
                60,
                536870912L,
                1.0,
                100000,
                180,
                2
        );
    }

    @Test
    void recoverStuckJobs_shouldPersistResetBeforeQueuePublication() {
        SimulationJob stuckJob = job(UUID.randomUUID(), false);
        stuckJob.setStatus(SimulationJob.JobStatus.RUNNING);
        stuckJob.setStartedAt(Instant.now().minusSeconds(300));
        stuckJob.setRetryCount(1);
        List<SavedJobState> savedStates = captureSavedStates();
        when(jobRepository.findStuckJobs(any(Instant.class))).thenReturn(List.of(stuckJob));
        when(jobQueueService.enqueueJob(stuckJob.getJobId())).thenReturn(JobQueueService.PublicationResult.CONFIRMED);

        workerService.recoverStuckJobs();

        assertThat(savedStates).hasSize(1);
        SavedJobState saved = savedStates.getFirst();
        assertThat(saved.status()).isEqualTo(SimulationJob.JobStatus.PENDING);
        assertThat(saved.retryCount()).isEqualTo(1);
        assertThat(saved.startedAt()).isNull();
        InOrder order = inOrder(jobRepository, jobQueueService);
        order.verify(jobRepository).save(stuckJob);
        order.verify(jobQueueService).enqueueJob(stuckJob.getJobId());
    }

    @Test
    void processJob_shouldStopWhenJobDoesNotExist() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findByJobId(jobId.toString())).thenReturn(Optional.empty());

        workerService.processJob(new JobMessage(jobId.toString()));

        verify(jobRepository).findByJobId(jobId.toString());
        verify(jobRepository, never()).claimJob(anyString(), any(Instant.class));
        verify(jobRepository, never()).save(any(SimulationJob.class));
        verifyNoInteractions(workspaceService, inputMaterializer, parserService, commandBuilder, dockerExecutor, storageService, jobQueueService);
    }

    @ParameterizedTest
    @EnumSource(value = SimulationJob.JobStatus.class, names = {"COMPLETED", "FAILED"})
    void processJob_shouldSkipWhenJobIsTerminal(SimulationJob.JobStatus status) throws Exception {
        UUID jobId = UUID.randomUUID();
        SimulationJob job = job(jobId, false);
        job.setStatus(status);
        when(jobRepository.findByJobId(jobId.toString())).thenReturn(Optional.of(job));

        workerService.processJob(new JobMessage(jobId.toString()));

        verify(jobRepository, never()).claimJob(anyString(), any(Instant.class));
        verify(jobRepository, never()).save(any(SimulationJob.class));
        verifyNoInteractions(workspaceService, inputMaterializer, parserService, commandBuilder, dockerExecutor, storageService, jobQueueService);
    }

    @Test
    void processJob_shouldStopWhenClaimIsRejected() throws Exception {
        UUID jobId = UUID.randomUUID();
        SimulationJob job = job(jobId, false);
        when(jobRepository.findByJobId(jobId.toString())).thenReturn(Optional.of(job));
        when(jobRepository.claimJob(eq(jobId.toString()), any(Instant.class))).thenReturn(0);

        workerService.processJob(new JobMessage(jobId.toString()));

        verify(jobRepository, never()).save(any(SimulationJob.class));
        verifyNoInteractions(workspaceService, inputMaterializer, parserService, commandBuilder, dockerExecutor, storageService);
    }

    @Test
    void processJob_shouldMaterializeAndExecuteDesignOnlyJobInAttemptWorkspace() throws Exception {
        UUID jobId = UUID.randomUUID();
        SimulationJob job = job(jobId, false);
        VerilatorOptions options = job.getVerilatorOptions();
        Path attempt = Files.createDirectory(tempDir.resolve("attempt"));
        String[] buildCommand = {"verilator", "--binary", "design.sv"};
        List<SavedJobState> savedStates = captureSavedStates();
        prepareClaim(job);
        when(workspaceService.createAttemptWorkspace()).thenReturn(attempt);
        when(parserService.resolveTopModule(attempt.resolve("design.sv"))).thenReturn("design");
        when(parserService.detectUvmUsage(attempt.resolve("design.sv"))).thenReturn(false);
        when(commandBuilder.buildCommand(options, "design", false, false)).thenReturn(buildCommand);
        when(dockerExecutor.runBuild(any(), any(), anyBoolean(), anyLong(), anyDouble(), anyInt(), anyInt(), anyString()))
                .thenReturn(new ContainerResult(true, "ok", 0));

        workerService.processJob(new JobMessage(jobId.toString()));

        verify(inputMaterializer).materialize(jobId, false, attempt);
        verify(parserService).resolveTopModule(attempt.resolve("design.sv"));
        verify(parserService).detectUvmUsage(attempt.resolve("design.sv"));
        verify(commandBuilder).buildCommand(options, "design", false, false);
        verify(dockerExecutor).runBuild(eq(attempt), same(buildCommand), eq(true), eq(536870912L), eq(1.0), eq(120), eq(100000), eq(jobId.toString()));
        verify(dockerExecutor, never()).runSimulation(any(), any(), anyLong(), anyDouble(), anyInt(), anyInt(), anyString(), anyString());
        assertThat(savedStates).hasSize(1);
        SavedJobState saved = savedStates.getFirst();
        assertThat(saved.status()).isEqualTo(SimulationJob.JobStatus.COMPLETED);
        assertThat(saved.retryCount()).isZero();
        assertThat(saved.completedAt()).isNotNull();
        assertThat(saved.errorMessage()).isNull();
        assertThat(saved.resultJson()).contains("\"passed\":true", "\"logs\":\"ok\"");
        verify(workspaceService).deleteAttemptWorkspace(attempt);
    }

    @Test
    void processJob_shouldFailBeforeExecutionWhenWorkspaceCannotBeEstablished() throws Exception {
        UUID jobId = UUID.randomUUID();
        SimulationJob job = job(jobId, false);
        IOException failure = new IOException("Failed to establish worker workspace");
        List<SavedJobState> savedStates = captureSavedStates();
        prepareClaim(job);
        when(workspaceService.createAttemptWorkspace()).thenThrow(failure);

        assertThatThrownBy(() -> workerService.processJob(new JobMessage(jobId.toString()))).isSameAs(failure);

        assertRetryableState(savedStates.getFirst(), 1);
        verifyNoInteractions(inputMaterializer, parserService, commandBuilder, dockerExecutor, storageService);
    }

    @Test
    void processJob_shouldUseFreshWorkspaceAndRematerializeEveryAttempt() throws Exception {
        UUID jobId = UUID.randomUUID();
        SimulationJob job = job(jobId, false);
        Path firstAttempt = Files.createDirectory(tempDir.resolve("attempt-1"));
        Path secondAttempt = Files.createDirectory(tempDir.resolve("attempt-2"));
        RuntimeException firstFailure = new RuntimeException("first attempt failed");
        List<SavedJobState> savedStates = captureSavedStates();
        prepareClaim(job);
        when(workspaceService.createAttemptWorkspace()).thenReturn(firstAttempt, secondAttempt);
        when(parserService.resolveTopModule(firstAttempt.resolve("design.sv"))).thenReturn("design");
        when(parserService.detectUvmUsage(firstAttempt.resolve("design.sv"))).thenReturn(false);
        when(parserService.resolveTopModule(secondAttempt.resolve("design.sv"))).thenReturn("design");
        when(parserService.detectUvmUsage(secondAttempt.resolve("design.sv"))).thenReturn(false);
        when(commandBuilder.buildCommand(any(), eq("design"), eq(false), eq(false)))
                .thenReturn(new String[]{"verilator"});
        when(dockerExecutor.runBuild(any(), any(), anyBoolean(), anyLong(), anyDouble(), anyInt(), anyInt(), anyString()))
                .thenThrow(firstFailure)
                .thenReturn(new ContainerResult(true, "ok", 0));

        assertThatThrownBy(() -> workerService.processJob(new JobMessage(jobId.toString()))).isSameAs(firstFailure);
        workerService.processJob(new JobMessage(jobId.toString()));

        verify(inputMaterializer).materialize(jobId, false, firstAttempt);
        verify(inputMaterializer).materialize(jobId, false, secondAttempt);
        verify(workspaceService).deleteAttemptWorkspace(firstAttempt);
        verify(workspaceService).deleteAttemptWorkspace(secondAttempt);
        assertThat(savedStates).hasSize(2);
        assertRetryableState(savedStates.get(0), 1);
        assertThat(savedStates.get(1).status()).isEqualTo(SimulationJob.JobStatus.COMPLETED);
        assertThat(savedStates.get(1).retryCount()).isEqualTo(1);
    }

    @Test
    void processJob_shouldPersistRetrySequenceAndSafePermanentMaterializationFailure() throws Exception {
        UUID jobId = UUID.randomUUID();
        SimulationJob job = job(jobId, true);
        Path firstAttempt = Files.createDirectory(tempDir.resolve("retry-1"));
        Path secondAttempt = Files.createDirectory(tempDir.resolve("retry-2"));
        Path thirdAttempt = Files.createDirectory(tempDir.resolve("retry-3"));
        InputMaterializationException failure = new InputMaterializationException("testbench.sv", "storage lookup failed");
        List<SavedJobState> savedStates = captureSavedStates();
        prepareClaim(job);
        when(workspaceService.createAttemptWorkspace()).thenReturn(firstAttempt, secondAttempt, thirdAttempt);
        doThrow(failure).when(inputMaterializer).materialize(eq(jobId), eq(true), any(Path.class));

        assertThatThrownBy(() -> workerService.processJob(new JobMessage(jobId.toString()))).isSameAs(failure);
        assertThatThrownBy(() -> workerService.processJob(new JobMessage(jobId.toString()))).isSameAs(failure);
        assertThatThrownBy(() -> workerService.processJob(new JobMessage(jobId.toString()))).isSameAs(failure);

        assertThat(savedStates).hasSize(3);
        assertRetryableState(savedStates.get(0), 1);
        assertRetryableState(savedStates.get(1), 2);
        SavedJobState permanent = savedStates.get(2);
        assertThat(permanent.retryCount()).isEqualTo(2);
        assertThat(permanent.status()).isEqualTo(SimulationJob.JobStatus.FAILED);
        assertThat(permanent.completedAt()).isNotNull();
        assertThat(permanent.errorMessage()).contains("testbench.sv", "storage lookup failed");
        verify(storageService, never()).deleteInput(any(), any());
        verifyNoInteractions(parserService, commandBuilder, dockerExecutor);
    }

    @Test
    void processJob_shouldPersistSafePermanentErrorWhenDependencyFailureIsUntrusted() throws Exception {
        UUID jobId = UUID.randomUUID();
        SimulationJob job = job(jobId, false);
        job.setRetryCount(2);
        Path attempt = Files.createDirectory(tempDir.resolve("unsafe-failure"));
        RuntimeException failure = new RuntimeException("credentials=storage-secret path=/private/attempt/design.sv");
        List<SavedJobState> savedStates = captureSavedStates();
        prepareClaim(job);
        when(workspaceService.createAttemptWorkspace()).thenReturn(attempt);
        prepareDesignOnlyCommand(attempt);
        when(dockerExecutor.runBuild(any(), any(), anyBoolean(), anyLong(), anyDouble(), anyInt(), anyInt(), anyString())).thenThrow(failure);

        assertThatThrownBy(() -> workerService.processJob(new JobMessage(jobId.toString()))).isSameAs(failure);

        SavedJobState saved = savedStates.getFirst();
        assertThat(saved.status()).isEqualTo(SimulationJob.JobStatus.FAILED);
        assertThat(saved.completedAt()).isNotNull();
        assertThat(saved.errorMessage()).isNotBlank().containsIgnoringCase("failed");
        assertThat(saved.errorMessage()).doesNotContain("credentials", "storage-secret", "/private/attempt");
    }

    @Test
    void processJob_shouldPreserveCompletedOutcomeWhenWorkspaceCleanupFails() throws Exception {
        UUID jobId = UUID.randomUUID();
        SimulationJob job = job(jobId, false);
        Path attempt = Files.createDirectory(tempDir.resolve("cleanup-failure"));
        List<SavedJobState> savedStates = captureSavedStates();
        prepareClaim(job);
        when(workspaceService.createAttemptWorkspace()).thenReturn(attempt);
        doThrow(new IOException("cleanup failed")).when(workspaceService).deleteAttemptWorkspace(attempt);
        prepareDesignOnlyCommand(attempt);
        when(dockerExecutor.runBuild(any(), any(), anyBoolean(), anyLong(), anyDouble(), anyInt(), anyInt(), anyString()))
                .thenReturn(new ContainerResult(true, "ok", 0));

        workerService.processJob(new JobMessage(jobId.toString()));

        SavedJobState saved = savedStates.getFirst();
        assertThat(saved.status()).isEqualTo(SimulationJob.JobStatus.COMPLETED);
        assertThat(saved.completedAt()).isNotNull();
        verify(workspaceService).deleteAttemptWorkspace(attempt);
    }

    @Test
    void processJob_shouldExecuteBinaryUvmTestbenchWithSimulationArguments() throws Exception {
        UUID jobId = UUID.randomUUID();
        VerilatorOptions options = new VerilatorOptions(BuildMode.BINARY, false, false, 0, false, false, 2, OptimizationLevel.O3,
                List.of(), List.of(), List.of(), List.of(), List.of("+seed=7", "+verbose"));
        SimulationJob job = job(jobId, true);
        job.setVerilatorOptions(options);
        Path attempt = Files.createDirectory(tempDir.resolve("binary-testbench"));
        String[] buildCommand = {"verilator", "--binary", "design.sv", "testbench.sv"};
        String[] simulationCommand = {"./obj_dir/Vtb", "+seed=7", "+verbose"};
        List<SavedJobState> savedStates = captureSavedStates();
        prepareClaim(job);
        when(workspaceService.createAttemptWorkspace()).thenReturn(attempt);
        when(parserService.resolveTopModule(attempt.resolve("testbench.sv"))).thenReturn("tb");
        when(parserService.detectUvmUsage(attempt.resolve("testbench.sv"))).thenReturn(true);
        when(commandBuilder.buildCommand(options, "tb", true, true)).thenReturn(buildCommand);
        when(dockerExecutor.runBuild(any(), any(), anyBoolean(), anyLong(), anyDouble(), anyInt(), anyInt(), anyString()))
                .thenReturn(new ContainerResult(true, "build logs", 0));
        when(commandBuilder.simulationCommand("tb", options.simArgs())).thenReturn(simulationCommand);
        when(dockerExecutor.runSimulation(any(), any(), anyLong(), anyDouble(), anyInt(), anyInt(), anyString(), anyString()))
                .thenReturn(new ContainerResult(true, "simulation logs", 0));

        workerService.processJob(new JobMessage(jobId.toString()));

        verify(inputMaterializer).materialize(jobId, true, attempt);
        verify(parserService).resolveTopModule(attempt.resolve("testbench.sv"));
        verify(parserService).detectUvmUsage(attempt.resolve("testbench.sv"));
        verify(commandBuilder).buildCommand(options, "tb", true, true);
        verify(dockerExecutor).runBuild(eq(attempt), same(buildCommand), eq(false), eq(536870912L), eq(1.0), eq(120), eq(100000), eq(jobId.toString()));
        verify(commandBuilder).simulationCommand("tb", options.simArgs());
        verify(dockerExecutor).runSimulation(eq(attempt), same(simulationCommand), eq(536870912L), eq(1.0), eq(60), eq(100000), eq("build logs"), eq(jobId.toString()));
        assertThat(savedStates.getFirst().status()).isEqualTo(SimulationJob.JobStatus.COMPLETED);
        assertThat(savedStates.getFirst().resultJson()).contains("\"logs\":\"simulation logs\"");
    }

    @Test
    void processJob_shouldUploadLogWaveformAndModelFromAttemptWorkspace() throws Exception {
        UUID jobId = UUID.randomUUID();
        VerilatorOptions options = new VerilatorOptions(BuildMode.CC_MODEL, true, false, 3, false, false, 2, OptimizationLevel.O3,
                List.of(), List.of(), List.of(), List.of(), List.of());
        SimulationJob job = job(jobId, true);
        job.setVerilatorOptions(options);
        Path attempt = Files.createDirectory(tempDir.resolve("artifacts"));
        Path waveform = attempt.resolve("trace.vcd");
        Path generatedModel = attempt.resolve("obj_dir/nested/model.cpp");
        List<SavedJobState> savedStates = captureSavedStates();
        prepareClaim(job);
        when(workspaceService.createAttemptWorkspace()).thenReturn(attempt);
        when(parserService.resolveTopModule(attempt.resolve("testbench.sv"))).thenReturn("tb");
        when(parserService.detectUvmUsage(attempt.resolve("testbench.sv"))).thenReturn(false);
        when(commandBuilder.buildCommand(options, "tb", true, false)).thenReturn(new String[]{"verilator", "--cc"});
        when(dockerExecutor.runBuild(any(), any(), anyBoolean(), anyLong(), anyDouble(), anyInt(), anyInt(), anyString())).thenAnswer(invocation -> {
            Files.writeString(waveform, "waveform bytes");
            Files.createDirectories(generatedModel.getParent());
            Files.writeString(generatedModel, "generated model");
            return new ContainerResult(true, "simulation log", 0);
        });

        workerService.processJob(new JobMessage(jobId.toString()));

        ArgumentCaptor<Path> logCaptor = ArgumentCaptor.forClass(Path.class);
        ArgumentCaptor<Path> modelCaptor = ArgumentCaptor.forClass(Path.class);
        verify(storageService).uploadOutput(eq(jobId), eq(Output.SIMULATION_LOG), logCaptor.capture());
        verify(storageService).uploadOutput(jobId, Output.WAVEFORM, waveform);
        verify(storageService).uploadOutput(eq(jobId), eq(Output.MODEL), modelCaptor.capture());
        assertThat(Files.readString(logCaptor.getValue())).isEqualTo("simulation log");
        try (ZipFile zip = new ZipFile(modelCaptor.getValue().toFile())) {
            assertThat(zip.getEntry("nested/model.cpp")).isNotNull();
            assertThat(zip.getInputStream(zip.getEntry("nested/model.cpp")).readAllBytes()).isEqualTo("generated model".getBytes(StandardCharsets.UTF_8));
        }
        verify(dockerExecutor, never()).runSimulation(any(), any(), anyLong(), anyDouble(), anyInt(), anyInt(), anyString(), anyString());
        assertThat(savedStates.getFirst().status()).isEqualTo(SimulationJob.JobStatus.COMPLETED);
    }

    @Test
    void processJob_shouldCompleteWhenBuildResultDoesNotPass() throws Exception {
        UUID jobId = UUID.randomUUID();
        SimulationJob job = job(jobId, false);
        Path attempt = Files.createDirectory(tempDir.resolve("failed-build"));
        List<SavedJobState> savedStates = captureSavedStates();
        prepareClaim(job);
        when(workspaceService.createAttemptWorkspace()).thenReturn(attempt);
        prepareDesignOnlyCommand(attempt);
        when(dockerExecutor.runBuild(any(), any(), anyBoolean(), anyLong(), anyDouble(), anyInt(), anyInt(), anyString()))
                .thenReturn(new ContainerResult(false, "compile error", 2));

        workerService.processJob(new JobMessage(jobId.toString()));

        SavedJobState saved = savedStates.getFirst();
        assertThat(saved.status()).isEqualTo(SimulationJob.JobStatus.COMPLETED);
        assertThat(saved.retryCount()).isZero();
        assertThat(saved.errorMessage()).isNull();
        assertThat(saved.resultJson()).contains("\"passed\":false", "\"logs\":\"compile error\"", "\"exitCode\":2");
        verify(dockerExecutor, never()).runSimulation(any(), any(), anyLong(), anyDouble(), anyInt(), anyInt(), anyString(), anyString());
    }

    private SimulationJob job(UUID jobId, boolean testbenchExpected) {
        return new SimulationJob(jobId.toString(), testbenchExpected, VerilatorOptions.defaults(), owner);
    }

    private void prepareClaim(SimulationJob job) {
        when(jobRepository.findByJobId(job.getJobId())).thenReturn(Optional.of(job));
        when(jobRepository.claimJob(eq(job.getJobId()), any(Instant.class))).thenReturn(1);
    }

    private void prepareDesignOnlyCommand(Path attempt) throws IOException {
        when(parserService.resolveTopModule(attempt.resolve("design.sv"))).thenReturn("design");
        when(parserService.detectUvmUsage(attempt.resolve("design.sv"))).thenReturn(false);
        when(commandBuilder.buildCommand(any(), eq("design"), eq(false), eq(false))).thenReturn(new String[]{"verilator"});
    }

    private List<SavedJobState> captureSavedStates() {
        List<SavedJobState> savedStates = new ArrayList<>();
        when(jobRepository.save(any(SimulationJob.class))).thenAnswer(invocation -> {
            SimulationJob job = invocation.getArgument(0);
            savedStates.add(new SavedJobState(job.getStatus(), job.getRetryCount(), job.getStartedAt(), job.getCompletedAt(), job.getErrorMessage(), job.getResultJson()));
            return job;
        });
        return savedStates;
    }

    private void assertRetryableState(SavedJobState state, int expectedRetryCount) {
        assertThat(state.retryCount()).isEqualTo(expectedRetryCount);
        assertThat(state.status()).isEqualTo(SimulationJob.JobStatus.PENDING);
        assertThat(state.startedAt()).isNull();
        assertThat(state.completedAt()).isNull();
    }

    private record SavedJobState(SimulationJob.JobStatus status, Integer retryCount, Instant startedAt,
                                 Instant completedAt, String errorMessage, String resultJson) {
    }
}

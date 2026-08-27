package app.verirun.service;

import app.verirun.docker.DockerExecutor;
import app.verirun.docker.DockerExecutor.ContainerResult;
import app.verirun.dto.JobMessage;
import app.verirun.dto.VerilatorOptions;
import app.verirun.dto.VerilatorOptions.BuildMode;
import app.verirun.entity.SimulationJob;
import app.verirun.exception.InputMaterializationException;
import app.verirun.repository.SimulationJobRepository;
import app.verirun.storage.SimulationStorageService;
import app.verirun.storage.SimulationStorageService.Output;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.sonus21.rqueue.annotation.RqueueListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Profile("worker")
public class WorkerService {

    private static final Logger log = LoggerFactory.getLogger(WorkerService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DockerExecutor dockerExecutor;
    private final SimulationJobRepository jobRepository;
    private final VerilatorCommandBuilder commandBuilder;
    private final VerilogParserService parserService;
    private final SimulationStorageService storageService;
    private final JobQueueService jobQueueService;
    private final WorkerWorkspaceService workspaceService;
    private final SimulationInputMaterializer inputMaterializer;

    private final int buildTimeoutSeconds;
    private final int runTimeoutSeconds;
    private final long maxMemoryBytes;
    private final double cpuLimit;
    private final int maxLogSize;
    private final int jobTimeoutSeconds;
    private final int maxRetries;

    public WorkerService(DockerExecutor dockerExecutor, SimulationJobRepository jobRepository, VerilatorCommandBuilder commandBuilder, VerilogParserService parserService, SimulationStorageService storageService, JobQueueService jobQueueService,
                         WorkerWorkspaceService workspaceService, SimulationInputMaterializer inputMaterializer, @Value("${app.worker.build-timeout-seconds:120}") int buildTimeoutSeconds, @Value("${app.worker.run-timeout-seconds:60}") int runTimeoutSeconds, @Value("${app.worker.max-memory-bytes:536870912}") long maxMemoryBytes,
                         @Value("${app.worker.cpu-limit:1}") double cpuLimit, @Value("${app.worker.max-log-size:100000}") int maxLogSize, @Value("${app.worker.job-timeout-seconds:180}") int jobTimeoutSeconds, @Value("${app.worker.max-retries:2}") int maxRetries) {

        this.dockerExecutor = dockerExecutor;
        this.jobRepository = jobRepository;
        this.commandBuilder = commandBuilder;
        this.parserService = parserService;
        this.storageService = storageService;
        this.jobQueueService = jobQueueService;
        this.workspaceService = workspaceService;
        this.inputMaterializer = inputMaterializer;
        this.buildTimeoutSeconds = buildTimeoutSeconds;
        this.runTimeoutSeconds = runTimeoutSeconds;
        this.maxMemoryBytes = maxMemoryBytes;
        this.cpuLimit = cpuLimit;
        this.maxLogSize = maxLogSize;
        this.jobTimeoutSeconds = jobTimeoutSeconds;
        this.maxRetries = maxRetries;
    }

    @RqueueListener(value = "${app.queue.job-name:verirun:job-queue}", numRetries = "${app.worker.max-retries:2}")
    public void processJob(JobMessage message) throws IOException {
        String jobId = message.getJobId();
        Optional<SimulationJob> jobOpt = jobRepository.findByJobId(jobId);

        if (jobOpt.isEmpty()) {
            log.warn("Job {} not found in database", jobId);
            return;
        }

        SimulationJob job = jobOpt.get();

        if (job.getStatus() == SimulationJob.JobStatus.COMPLETED || job.getStatus() == SimulationJob.JobStatus.FAILED) {
            log.info("Job {} already in terminal state {}, skipping", jobId, job.getStatus());
            return;
        }

        Instant startedAt = Instant.now();
        int claimed = jobRepository.claimJob(jobId, startedAt);

        if (claimed == 0) {
            log.warn("Job {} was already claimed by another worker or is not in PENDING state", jobId);
            return;
        }

        log.info("Successfully claimed and processing job {}", jobId);

        job.setStatus(SimulationJob.JobStatus.RUNNING);
        job.setStartedAt(startedAt);

        Path attemptWorkspace = null;

        try {
            attemptWorkspace = workspaceService.createAttemptWorkspace();

            UUID durableJobId = UUID.fromString(jobId);
            inputMaterializer.materialize(durableJobId, job.isTestbenchExpected(), attemptWorkspace);

            ContainerResult result = executeSimulation(job, attemptWorkspace);
            uploadArtifacts(attemptWorkspace, durableJobId, result);

            job.setStatus(SimulationJob.JobStatus.COMPLETED);
            job.setResultJson(MAPPER.writeValueAsString(result));
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);

            log.info("Job {} completed and uploaded to storage", jobId);
        } catch (Exception e) {
            log.error("Job {} failed", jobId, e);
            handleJobFailure(job, e);
            throw e;
        } finally {
            cleanupAttemptWorkspace(attemptWorkspace, jobId);
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void recoverStuckJobs() {
        Instant cutoff = Instant.now().minusSeconds(jobTimeoutSeconds);
        List<SimulationJob> stuckJobs = jobRepository.findStuckJobs(cutoff);

        for (SimulationJob job : stuckJobs) {
            log.warn("Recovering stuck job {} (running since {})", job.getJobId(), job.getStartedAt());

            job.setStatus(SimulationJob.JobStatus.PENDING);
            job.setStartedAt(null);
            jobRepository.save(job);

            JobQueueService.PublicationResult publicationResult = jobQueueService.enqueueJob(job.getJobId());

            if (publicationResult == JobQueueService.PublicationResult.CONFIRMED) {
                log.info("Re-enqueued stuck job {}", job.getJobId());
            } else {
                log.warn("Queue publication was not confirmed for recovered stuck job {}", job.getJobId());
            }
        }
    }

    private void handleJobFailure(SimulationJob job, Exception e) {
        int retryCount = job.getRetryCount() != null ? job.getRetryCount() : 0;

        if (retryCount < maxRetries) {
            job.setRetryCount(retryCount + 1);
            job.setStatus(SimulationJob.JobStatus.PENDING);
            job.setStartedAt(null);
            job.setCompletedAt(null);

            log.warn("Job {} failed, retrying (attempt {}/{})", job.getJobId(), retryCount + 1, maxRetries);
        } else {
            job.setStatus(SimulationJob.JobStatus.FAILED);
            job.setErrorMessage(permanentErrorMessage(e));
            job.setCompletedAt(Instant.now());

            log.error("Job {} failed permanently after {} retries", job.getJobId(), maxRetries, e);
        }

        jobRepository.save(job);
    }

    private String permanentErrorMessage(Exception exception) {
        if (exception instanceof InputMaterializationException) {
            return exception.getMessage();
        }

        return "Worker execution failed";
    }

    private ContainerResult executeSimulation(SimulationJob job, Path jobDir) throws IOException {

        VerilatorOptions options = job.getVerilatorOptions();
        boolean hasTestbench = job.isTestbenchExpected();

        Path targetFile = hasTestbench ? jobDir.resolve("testbench.sv") : jobDir.resolve("design.sv");

        String topModule = parserService.resolveTopModule(targetFile);
        boolean usesUvm = parserService.detectUvmUsage(targetFile);

        if (usesUvm && !hasTestbench) {
            throw new IllegalArgumentException("Testbench is required for UVM mode");
        }

        String[] buildCmd = commandBuilder.buildCommand(options, topModule, hasTestbench, usesUvm);

        boolean needsRun = options.buildMode() == BuildMode.BINARY && hasTestbench;

        log.info("Executing verilator build for job {}", job.getJobId());

        ContainerResult buildResult = dockerExecutor.runBuild(jobDir, buildCmd, !needsRun, maxMemoryBytes, cpuLimit, buildTimeoutSeconds, maxLogSize, job.getJobId());

        if (!buildResult.passed()) {
            return buildResult;
        }

        if (needsRun) {
            String[] simCmd = commandBuilder.simulationCommand(topModule, options.simArgs());

            return dockerExecutor.runSimulation(jobDir, simCmd, maxMemoryBytes, cpuLimit, runTimeoutSeconds, maxLogSize, buildResult.logs(), job.getJobId());
        }

        return buildResult;
    }

    private void uploadArtifacts(Path jobDir, UUID jobId, ContainerResult result) throws IOException {

        Path logsFile = jobDir.resolve("simulation.log");
        Files.writeString(logsFile, result.logs());
        storageService.uploadOutput(jobId, Output.SIMULATION_LOG, logsFile);

        List<Path> waveforms;

        try (var paths = Files.list(jobDir)) {
            waveforms = paths.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".vcd")).toList();
        }

        if (waveforms.size() > 1) {
            throw new IOException("Multiple waveform files were produced");
        }

        if (!waveforms.isEmpty()) {
            storageService.uploadOutput(jobId, Output.WAVEFORM, waveforms.getFirst());

            log.info("Uploaded waveform for job {}", jobId);
        }

        Path objDir = jobDir.resolve("obj_dir");

        if (Files.isDirectory(objDir)) {
            Path zipFile = jobDir.resolve("model.zip");
            zipDirectory(objDir, zipFile);
            storageService.uploadOutput(jobId, Output.MODEL, zipFile);

            log.info("Uploaded model for job {}", jobId);
        }
    }

    private void zipDirectory(Path sourceDir, Path zipFilePath) throws IOException {

        List<Path> files;

        try (var paths = Files.walk(sourceDir)) {
            files = paths.filter(Files::isRegularFile).toList();
        }

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFilePath))) {

            for (Path file : files) {
                String entryName = sourceDir.relativize(file).toString().replace("\\", "/");

                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private void cleanupAttemptWorkspace(Path attemptWorkspace, String jobId) {

        if (attemptWorkspace == null) {
            return;
        }

        try {
            workspaceService.deleteAttemptWorkspace(attemptWorkspace);
            log.debug("Cleaned up worker workspace for job {}", jobId);
        } catch (Exception e) {
            log.warn("Failed to clean up worker workspace for job {}", jobId, e);
        }
    }
}
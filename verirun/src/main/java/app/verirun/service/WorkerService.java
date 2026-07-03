package app.verirun.service;

import app.verirun.docker.DockerExecutor;
import app.verirun.docker.DockerExecutor.ContainerResult;
import app.verirun.dto.JobMessage;
import app.verirun.dto.VerilatorOptions;
import app.verirun.dto.VerilatorOptions.BuildMode;
import app.verirun.entity.SimulationJob;
import app.verirun.repository.SimulationJobRepository;
import app.verirun.storage.ArtifactStorageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.sonus21.rqueue.annotation.RqueueListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
    private final ArtifactStorageService storageService;
    private final JobQueueService jobQueueService;

    private final Path workspaceBasePath;
    private final int buildTimeoutSeconds;
    private final int runTimeoutSeconds;
    private final long maxMemoryBytes;
    private final long cpuLimit;
    private final int maxLogSize;
    private final int jobTimeoutSeconds;
    private final int maxRetries;

    public WorkerService(
            DockerExecutor dockerExecutor,
            SimulationJobRepository jobRepository,
            VerilatorCommandBuilder commandBuilder,
            VerilogParserService parserService,
            ArtifactStorageService storageService,
            JobQueueService jobQueueService,
            @Value("${app.workspace.base-path:./verirun-workspace}") String workspaceBasePath,
            @Value("${app.worker.build-timeout-seconds:120}") int buildTimeoutSeconds,
            @Value("${app.worker.run-timeout-seconds:60}") int runTimeoutSeconds,
            @Value("${app.worker.max-memory-bytes:536870912}") long maxMemoryBytes,
            @Value("${app.worker.cpu-limit:1}") long cpuLimit,
            @Value("${app.worker.max-log-size:100000}") int maxLogSize,
            @Value("${app.worker.job-timeout-seconds:180}") int jobTimeoutSeconds,
            @Value("${app.worker.max-retries:2}") int maxRetries) {

        this.dockerExecutor = dockerExecutor;
        this.jobRepository = jobRepository;
        this.commandBuilder = commandBuilder;
        this.parserService = parserService;
        this.storageService = storageService;
        this.jobQueueService = jobQueueService;
        this.workspaceBasePath = Paths.get(workspaceBasePath);
        this.buildTimeoutSeconds = buildTimeoutSeconds;
        this.runTimeoutSeconds = runTimeoutSeconds;
        this.maxMemoryBytes = maxMemoryBytes;
        this.cpuLimit = cpuLimit;
        this.maxLogSize = maxLogSize;
        this.jobTimeoutSeconds = jobTimeoutSeconds;
        this.maxRetries = maxRetries;
    }

    @RqueueListener(value = "${app.queue.job-name}", numRetries = "${app.worker.max-retries:2}")
    public void processJob(JobMessage message) throws IOException {
        String jobId = message.getJobId();
        Optional<SimulationJob> jobOpt = jobRepository.findByJobId(jobId);

        if (jobOpt.isEmpty()) {
            log.warn("Job {} not found in database", jobId);
            return;
        }

        SimulationJob job = jobOpt.get();

        if (job.getStatus() == SimulationJob.JobStatus.COMPLETED ||
                job.getStatus() == SimulationJob.JobStatus.FAILED) {
            log.info("Job {} already in terminal state {}, skipping", jobId, job.getStatus());
            return;
        }

        int claimed = jobRepository.claimJob(jobId, Instant.now());
        if (claimed == 0) {
            log.warn("Job {} was already claimed by another worker or is not in PENDING state", jobId);
            return;
        }

        log.info("Successfully claimed and processing job {}", jobId);

        job.setStatus(SimulationJob.JobStatus.RUNNING);
        job.setStartedAt(Instant.now());

        try {
            ContainerResult result = executeSimulation(job);
            uploadArtifactsToS3(Paths.get(job.getDirectoryPath()), job.getJobId(), result);

            job.setStatus(SimulationJob.JobStatus.COMPLETED);
            job.setResultJson(resultToJson(result));
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);

            log.info("Job {} completed and uploaded to S3", jobId);

        } catch (Exception e) {
            log.error("Job {} failed", jobId, e);
            handleJobFailure(job, e);
            throw e;
        } finally {
            deleteLocalDirectory(Paths.get(job.getDirectoryPath()));
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

            jobQueueService.enqueueJob(job.getJobId());
            log.info("Re-enqueued stuck job {}", job.getJobId());
        }
    }

    private void handleJobFailure(SimulationJob job, Exception e) {
        int retryCount = job.getRetryCount() != null ? job.getRetryCount() : 0;

        if (retryCount < maxRetries) {
            job.setRetryCount(retryCount + 1);
            job.setStatus(SimulationJob.JobStatus.PENDING);
            job.setStartedAt(null);
            log.warn("Job {} failed, retrying (attempt {}/{})", job.getJobId(), retryCount + 1, maxRetries);
        } else {
            job.setStatus(SimulationJob.JobStatus.FAILED);
            job.setErrorMessage(Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
            job.setCompletedAt(Instant.now());
            log.error("Job {} failed permanently after {} retries", job.getJobId(), maxRetries, e);
        }

        jobRepository.save(job);
    }

    private String resultToJson(ContainerResult result) {
        try {
            return MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize result", e);
            return "{\"passed\":false,\"logs\":\"Serialization error\",\"exitCode\":-1}";
        }
    }

    private ContainerResult executeSimulation(SimulationJob job) throws IOException {
        Path jobDir = Paths.get(job.getDirectoryPath());

        if (!Files.exists(jobDir)) {
            Files.createDirectories(jobDir);
        }

        Path realJobDir = jobDir.toRealPath();

        if (!Files.exists(workspaceBasePath)) {
            Files.createDirectories(workspaceBasePath);
        }
        Path realWorkspaceBase = workspaceBasePath.toRealPath();

        if (!realJobDir.startsWith(realWorkspaceBase)) {
            throw new SecurityException("Invalid workspace path: job directory outside workspace base");
        }

        VerilatorOptions options = job.getVerilatorOptions();
        boolean hasTestbench = hasTestbench(jobDir);

        Path targetFile = hasTestbench ? jobDir.resolve("testbench.sv") : jobDir.resolve("design.sv");
        String topModule = parserService.resolveTopModule(targetFile);
        boolean usesUvm = parserService.detectUvmUsage(targetFile);

        if (usesUvm && !hasTestbench) {
            throw new IllegalArgumentException("Testbench is required for UVM mode");
        }

        String[] buildCmd = commandBuilder.buildCommand(options, topModule, hasTestbench, usesUvm);
        log.info("Array: {}", Arrays.toString(buildCmd));

        boolean needsRun = options.buildMode() == BuildMode.BINARY && hasTestbench;

        log.info("Executing verilator build for job {}", job.getJobId());
        ContainerResult buildResult = dockerExecutor.runBuild(jobDir, buildCmd,
                !needsRun, maxMemoryBytes, cpuLimit, buildTimeoutSeconds, maxLogSize, job.getJobId());

        if (!buildResult.passed()) {
            return buildResult;
        }

        if (needsRun) {
            String[] simCmd = commandBuilder.simulationCommand(topModule, options.simArgs());
            return dockerExecutor.runSimulation(jobDir, simCmd,
                    maxMemoryBytes, cpuLimit, runTimeoutSeconds, maxLogSize, buildResult.logs(), job.getJobId());
        }

        return buildResult;
    }

    private void uploadArtifactsToS3(Path jobDir, String jobId, ContainerResult result) {
        try {
            Path logsFile = jobDir.resolve("simulation.log");
            Files.writeString(logsFile, result.logs() != null ? result.logs() : "No logs available.");
            storageService.uploadArtifact(jobId, "simulation.log", logsFile);

            try (var stream = Files.list(jobDir)) {
                List<Path> waveforms = stream.filter(p ->
                        p.getFileName().toString().toLowerCase().endsWith(".vcd")
                ).toList();

                for (Path waveform : waveforms) {
                    String standardName = "waveform.vcd";
                    Path standardPath = jobDir.resolve(standardName);

                    if (!waveform.getFileName().toString().equals(standardName)) {
                        Files.move(waveform, standardPath, StandardCopyOption.REPLACE_EXISTING);
                    }

                    storageService.uploadArtifact(jobId, standardName, standardPath);
                    log.info("Uploaded {} for job {}", standardName, jobId);
                }
            }

            Path objDir = jobDir.resolve("obj_dir");
            if (Files.exists(objDir) && Files.isDirectory(objDir)) {
                Path zipFile = jobDir.resolve("model.zip");
                zipDirectory(objDir, zipFile);
                storageService.uploadArtifact(jobId, "model.zip", zipFile);
                Files.deleteIfExists(zipFile);
                log.info("Uploaded model.zip for job {}", jobId);
            }

        } catch (IOException e) {
            log.error("Failed to upload artifacts for job {}", jobId, e);
        }
    }

    private void zipDirectory(Path sourceDir, Path zipFilePath) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFilePath))) {
            Files.walk(sourceDir)
                    .filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
                        String entryName = sourceDir.relativize(path).toString().replace("\\", "/");
                        ZipEntry zipEntry = new ZipEntry(entryName);
                        try {
                            zos.putNextEntry(zipEntry);
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            log.error("Failed to add {} to zip", path, e);
                        }
                    });
        }
    }

    private void deleteLocalDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            FileSystemUtils.deleteRecursively(dir);
            log.debug("Cleaned up local scratchpad: {}", dir);
        } catch (Exception e) {
            log.warn("Failed to clean up local scratchpad: {}", dir, e);
        }
    }

    private boolean hasTestbench(Path jobDir) {
        return Files.exists(jobDir.resolve("testbench.sv"));
    }
}
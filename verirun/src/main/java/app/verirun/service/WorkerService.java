package app.verirun.service;

import app.verirun.docker.DockerExecutor;
import app.verirun.docker.DockerExecutor.ContainerResult;
import app.verirun.dto.JobMessage;
import app.verirun.dto.VerilatorOptions;
import app.verirun.dto.VerilatorOptions.BuildMode;
import app.verirun.entity.SimulationJob;
import app.verirun.repository.SimulationJobRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.sonus21.rqueue.annotation.RqueueListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
@Profile("worker")
public class WorkerService {
    private static final Logger log = LoggerFactory.getLogger(WorkerService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final java.util.regex.Pattern MODULE_PATTERN =
            java.util.regex.Pattern.compile("module\\s+([a-zA-Z_][a-zA-Z0-9_]*)");

    private final DockerExecutor dockerExecutor;
    private final SimulationJobRepository jobRepository;
    private final VerilatorCommandBuilder commandBuilder;

    private static final int BUILD_TIMEOUT_SECONDS = 120;
    private static final int RUN_TIMEOUT_SECONDS = 60;
    private static final long MAX_MEMORY_BYTES = 512 * 1024 * 1024L;
    private static final long CPU_LIMIT = 1L;
    private static final String MAX_RETRIES = "2";
    private static final int MAX_LOG_SIZE = 100_000;
    private static final String WORKSPACE_BASE = System.getProperty("java.io.tmpdir") + "/verirun";
    private static final int JOB_TIMEOUT_SECONDS = 180;

    public WorkerService(DockerExecutor dockerExecutor,
                         SimulationJobRepository jobRepository,
                         VerilatorCommandBuilder commandBuilder) {
        this.dockerExecutor = dockerExecutor;
        this.jobRepository = jobRepository;
        this.commandBuilder = commandBuilder;
    }

    @RqueueListener(value = JobQueueService.JOB_QUEUE, numRetries = MAX_RETRIES)
    public void processJob(JobMessage message) throws IOException {
        String jobId = message.getJobId();
        Optional<SimulationJob> jobOpt = jobRepository.findByJobId(jobId);
        if (jobOpt.isEmpty()) {
            log.warn("Job {} not found in database", jobId);
            return;
        }

        SimulationJob job = jobOpt.get();

        if (job.getStatus() == SimulationJob.JobStatus.COMPLETED) {
            log.info("Job {} already completed, skipping", jobId);
            return;
        }
        if (job.getStatus() == SimulationJob.JobStatus.RUNNING) {
            log.warn("Job {} already running, skipping", jobId);
            return;
        }

        log.info("Processing job {}", jobId);

        try {
            job.setStatus(SimulationJob.JobStatus.RUNNING);
            job.setStartedAt(Instant.now());
            jobRepository.save(job);

            ContainerResult result = executeSimulation(job);

            job.setStatus(SimulationJob.JobStatus.COMPLETED);
            job.setResultJson(resultToJson(result));
            job.setCompletedAt(Instant.now());
            job.setCleanupScheduledAt(Instant.now().plusSeconds(3600));
            job.setCleanedUp(false);
            jobRepository.save(job);

            log.info("Job {} completed successfully", jobId);

        } catch (Exception e) {
            log.error("Job {} failed", jobId, e);
            handleJobFailure(job, e);
            throw e;
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void recoverStuckJobs() {
        Instant cutoff = Instant.now().minusSeconds(JOB_TIMEOUT_SECONDS);
        List<SimulationJob> stuckJobs = jobRepository.findStuckJobs(cutoff);

        for (SimulationJob job : stuckJobs) {
            log.warn("Recovering stuck job {} (running since {})", job.getJobId(), job.getStartedAt());

            int retryCount = job.getRetryCount() != null ? job.getRetryCount() : 0;

            if (retryCount < Integer.parseInt(MAX_RETRIES)) {
                job.setRetryCount(retryCount + 1);
                job.setStatus(SimulationJob.JobStatus.PENDING);
                job.setStartedAt(null);
                jobRepository.save(job);

                log.info("Job {} requeued for retry (attempt {}/{})", job.getJobId(), retryCount + 1, MAX_RETRIES);
            } else {
                job.setStatus(SimulationJob.JobStatus.FAILED);
                job.setErrorMessage("Job timed out after " + MAX_RETRIES + " retries");
                job.setCompletedAt(Instant.now());
                job.setCleanupScheduledAt(Instant.now().plusSeconds(300));
                job.setCleanedUp(false);
                jobRepository.save(job);

                log.error("Job {} failed permanently - stuck after {} retries", job.getJobId(), MAX_RETRIES);
            }
        }

        if (!stuckJobs.isEmpty()) {
            jobRepository.saveAll(stuckJobs);
            log.info("Recovered {} stuck jobs", stuckJobs.size());
        }
    }

    private void handleJobFailure(SimulationJob job, Exception e) {
        String jobId = job.getJobId();
        int retryCount = job.getRetryCount() != null ? job.getRetryCount() : 0;

        if (retryCount < Integer.parseInt(MAX_RETRIES)) {
            job.setRetryCount(retryCount + 1);
            job.setStatus(SimulationJob.JobStatus.PENDING);
            job.setStartedAt(null);
            jobRepository.save(job);

            log.warn("Job {} failed, retrying (attempt {}/{})", jobId, retryCount + 1, MAX_RETRIES);
        } else {
            job.setStatus(SimulationJob.JobStatus.FAILED);
            job.setErrorMessage(Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
            job.setCompletedAt(Instant.now());
            job.setCleanupScheduledAt(Instant.now().plusSeconds(300));
            job.setCleanedUp(false);
            jobRepository.save(job);

            log.error("Job {} failed permanently after {} retries", jobId, MAX_RETRIES, e);
        }
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
        Path realWorkspaceBase = Paths.get(WORKSPACE_BASE).toRealPath();
        if (!realJobDir.startsWith(realWorkspaceBase)) {
            throw new SecurityException("Invalid workspace path: job directory outside workspace base");
        }

        try (var stream = Files.list(jobDir)) {
            stream.forEach(path -> {
                String fileName = path.getFileName().toString();
                if (!fileName.equals("design.sv") && !fileName.equals("testbench.sv")) {
                    throw new SecurityException("Unexpected file in job directory: " + fileName);
                }
            });
        }

        VerilatorOptions options = job.getVerilatorOptions();
        boolean hasTestbench = hasTestbench(jobDir);
        String topModule = resolveTopModule(jobDir);
        boolean usesUvm = detectUvmUsage(jobDir);

        if (usesUvm && !hasTestbench) {
            throw new IllegalArgumentException("Testbench is required for UVM mode");
        }

        String[] buildCmd = commandBuilder.buildCommand(options, topModule, hasTestbench, usesUvm);
        log.info("Array: {}", Arrays.toString(buildCmd));

        boolean needsRun = options.buildMode() == BuildMode.BINARY && hasTestbench;

        log.info("Executing verilator build for job {}", job.getJobId());
        ContainerResult buildResult = dockerExecutor.runBuild(jobDir, buildCmd,
                !needsRun, MAX_MEMORY_BYTES, CPU_LIMIT, BUILD_TIMEOUT_SECONDS, MAX_LOG_SIZE, job.getJobId());

        if (!buildResult.passed()) {
            return buildResult;
        }

        if (needsRun) {
            String[] simCmd = commandBuilder.simulationCommand(topModule, options.simArgs());
            return dockerExecutor.runSimulation(jobDir, simCmd,
                    MAX_MEMORY_BYTES, CPU_LIMIT, RUN_TIMEOUT_SECONDS, MAX_LOG_SIZE, buildResult.logs(), job.getJobId());
        }

        return buildResult;
    }

    private boolean hasTestbench(Path jobDir) {
        return Files.exists(jobDir.resolve("testbench.sv"));
    }

    private String resolveTopModule(Path jobDir) {
        try {
            Path testbenchPath = jobDir.resolve("testbench.sv");
            if (!Files.exists(testbenchPath)) {
                return "design_top";
            }

            String testbenchCode = Files.readString(testbenchPath);
            java.util.regex.Matcher matcher = MODULE_PATTERN.matcher(testbenchCode);
            if (matcher.find()) {
                String moduleName = matcher.group(1);
                if (!moduleName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                    throw new IllegalArgumentException("Invalid module name in testbench");
                }
                return moduleName;
            }
            throw new IllegalArgumentException("No 'module' declaration found in testbench");
        } catch (IOException e) {
            log.warn("Could not read testbench, using default top module", e);
            return "design_top";
        }
    }

    private boolean detectUvmUsage(Path jobDir) {
        try {
            Path testbenchPath = jobDir.resolve("testbench.sv");
            if (!Files.exists(testbenchPath)) {
                return false;
            }

            String testbench = Files.readString(testbenchPath).toLowerCase();
            return testbench.contains("uvm_pkg") ||
                    testbench.contains("uvm_macros") ||
                    testbench.contains("`uvm") ||
                    testbench.contains("import uvm_pkg");
        } catch (IOException e) {
            return false;
        }
    }
}

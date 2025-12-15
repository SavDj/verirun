package app.verirun.service;

import app.verirun.dto.SimulationRequest;
import app.verirun.dto.SimulationResult;
import app.verirun.entity.SimulationJob;
import app.verirun.repository.SimulationJobRepository;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.async.ResultCallbackTemplate;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class SimulationService {
    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final DockerClient dockerClient;
    private final SimulationJobRepository jobRepository;
    private final ScheduledExecutorService cleanupExecutor;
    private static final String VERILATOR_IMAGE = "verirun/verilator-uvm:latest";
    private static final int TIMEOUT_SECONDS = 120;
    private static final long MAX_MEMORY_BYTES = 1024 * 1024 * 1024;
    private static final long CPU_LIMIT = 2;
    private static final String WORKSPACE_BASE = System.getProperty("java.io.tmpdir") + "/verirun";
    private static final int CLEANUP_DELAY_MINUTES = 60;


    @Autowired
    public SimulationService(DockerClient dockerClient, SimulationJobRepository jobRepository) {
        this.dockerClient = dockerClient;
        this.jobRepository = jobRepository;
        this.cleanupExecutor = Executors.newScheduledThreadPool(1);

        cleanupExecutor.scheduleAtFixedRate(this::cleanupOldJobs, 1, 60, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down cleanup executor");
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public SimulationResult runSimulation(SimulationRequest request) throws IOException, TimeoutException {
        String jobId = UUID.randomUUID().toString();
        Path jobDir = createJobDirectory(jobId);

        SimulationJob job = new SimulationJob(jobId, jobDir.toString());
        jobRepository.save(job);

        try {
            Files.write(jobDir.resolve("design.sv"), request.designCode().getBytes());
            if (request.testbenchCode() != null && !request.testbenchCode().trim().isEmpty()) {
                Files.write(jobDir.resolve("testbench.sv"), request.testbenchCode().getBytes());
            }

            String verilatorCmd = buildVerilatorCommand(request);
            boolean hasTestbench = request.testbenchCode() != null && !request.testbenchCode().trim().isEmpty();
            String topModule = extractTopModuleFromTestbench(request.testbenchCode());
            log.info("Executing command: {}", verilatorCmd);
            ContainerResult innerResult = executeInContainer(jobDir, verilatorCmd, topModule, hasTestbench);

            job.setStatus(SimulationJob.JobStatus.COMPLETED);
            jobRepository.save(job);

            scheduleJobCleanup(jobId, CLEANUP_DELAY_MINUTES);

            return new SimulationResult(jobId, innerResult.passed(), innerResult.logs(), innerResult.exitCode());

        } catch (Exception e) {
            job.setStatus(SimulationJob.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            jobRepository.save(job);

            scheduleJobCleanup(jobId, 5);

            throw e;
        }
    }

    private void scheduleJobCleanup(String jobId, int delayMinutes) {
        cleanupExecutor.schedule(() -> {
            try {
                cleanupJob(jobId);
            } catch (Exception e) {
                log.warn("Failed to clean up job {}: {}", jobId, e.getMessage());
            }
        }, delayMinutes, TimeUnit.MINUTES);
    }

    public void cleanupJob(String jobId) {
        Optional<SimulationJob> jobOpt = jobRepository.findByJobId(jobId);
        if (jobOpt.isPresent() && !jobOpt.get().isCleanedUp()) {
            SimulationJob job = jobOpt.get();

            try {
                Path jobDir = Paths.get(job.getDirectoryPath());
                deleteRecursively(jobDir);
                jobRepository.markAsCleanedUp(jobId);
                log.info("Cleaned up job: {}", jobId);
            } catch (Exception e) {
                log.warn("Failed to clean up job {}: {}", jobId, e.getMessage());
            }
        }
    }

    private Path createJobDirectory(String jobId) throws IOException {
        Path baseDir = Paths.get(WORKSPACE_BASE);

        if (!Files.exists(baseDir)) {
            Files.createDirectories(baseDir);
            log.debug("Created base directory: {}", baseDir);
        }

        Path jobDir = baseDir.resolve(jobId);
        Files.createDirectories(jobDir);

        log.debug("Created job directory: {}", jobDir);
        return jobDir;
    }

    private void cleanupOldJobs() {
        try {
            Instant cutoff = Instant.now().minusSeconds(86400);

            List<SimulationJob> oldJobs = jobRepository
                    .findByCleanedUpFalseAndCleanupScheduledAtBefore(cutoff);

            log.debug("Found {} old jobs to clean up", oldJobs.size());

            for (SimulationJob job : oldJobs) {
                cleanupJob(job.getJobId());
            }
        } catch (Exception e) {
            log.error("Error during periodic job cleanup", e);
        }
    }

    private String buildVerilatorCommand(SimulationRequest request) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("cd /workspace && ");
        boolean usesUvm = detectUvmUsage(request);
        boolean hasTestbench = request.testbenchCode() != null && !request.testbenchCode().trim().isEmpty();

        String topModule = extractTopModuleFromTestbench(request.testbenchCode());

        if (usesUvm && !hasTestbench) {
            throw new IllegalArgumentException("Testbench is required for UVM mode");
        }

        if (request.generateModelOnly()) {
            cmd.append("verilator --timing --cc --Mdir model_out --top-module ").append(topModule);
        } else {
            if (hasTestbench) {
                cmd.append("verilator --timing --binary -j $(nproc) --top-module ").append(topModule);
            } else {
                cmd.append("verilator --timing --cc --top-module ").append(topModule);
            }
        }

        if (usesUvm) {
            cmd.append(" -Wno-fatal +incdir+$UVM_HOME +define+UVM_NO_DPI +incdir+. ")
                    .append("$UVM_HOME/uvm_pkg.sv ");
        }

        cmd.append(" design.sv");

        if (hasTestbench) {
            cmd.append(" testbench.sv");
        }

        if (!hasTestbench) {
            cmd.append(" && echo 'Elaboration successful. No testbench provided.'");
        }

        return cmd.toString();
    }

    private String extractTopModuleFromTestbench(String testbenchCode) {
        if (testbenchCode == null || testbenchCode.trim().isEmpty()) {
            return "design_top";
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("module\\s+([a-zA-Z_][a-zA-Z0-9_]*)");
        java.util.regex.Matcher matcher = pattern.matcher(testbenchCode);
        if (matcher.find()) {
            String moduleName = matcher.group(1);
            if (!moduleName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                throw new IllegalArgumentException("Invalid module name in testbench");
            }
            return moduleName;
        }
        throw new IllegalArgumentException("No 'module' declaration found in testbench");
    }

    private record ContainerResult(boolean passed, String logs, int exitCode) {}

    private ContainerResult executeInContainer(Path jobDir, String command, String topModule, boolean hasTestbench) {
        String containerName = "verirun-" + java.util.UUID.randomUUID().toString();

        try {
            CreateContainerResponse buildContainer = dockerClient.createContainerCmd(VERILATOR_IMAGE)
                    .withName(containerName + "-build")
                    .withCmd("sh", "-c", command)
                    .withHostConfig(com.github.dockerjava.api.model.HostConfig.newHostConfig()
                            .withBinds(com.github.dockerjava.api.model.Bind.parse(jobDir.toAbsolutePath() + ":/workspace"))
                            .withMemory(MAX_MEMORY_BYTES)
                            .withCpuCount(CPU_LIMIT)
                            .withNetworkMode("none")
                            .withReadonlyRootfs(false))
                    .withVolumes(new com.github.dockerjava.api.model.Volume("/workspace"))
                    .withWorkingDir("/workspace")
                    .exec();

            dockerClient.startContainerCmd(buildContainer.getId()).exec();

            WaitContainerResultCallback callback = new WaitContainerResultCallback();
            dockerClient.waitContainerCmd(buildContainer.getId()).exec(callback);

            boolean completed = callback.awaitCompletion(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!completed) {
                dockerClient.killContainerCmd(buildContainer.getId()).exec();

                WaitContainerResultCallback killCallback = new WaitContainerResultCallback();
                dockerClient.waitContainerCmd(buildContainer.getId()).exec(killCallback);
                killCallback.awaitCompletion(5, TimeUnit.SECONDS);

                return new ContainerResult(false,
                        "Build timed out after " + TIMEOUT_SECONDS + " seconds", -1);
            }

            Long buildExitCode = dockerClient.inspectContainerCmd(buildContainer.getId())
                    .exec().getState().getExitCodeLong();
            LogToStringCallback logCallback = new LogToStringCallback();
            dockerClient.logContainerCmd(buildContainer.getId())
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withSince(0)
                    .exec(logCallback)
                    .awaitCompletion(5, TimeUnit.SECONDS);

            String buildLogs = logCallback.toString();

            if (buildExitCode == null || buildExitCode != 0) {
                return new ContainerResult(false, "BUILD FAILED:\n" + buildLogs,
                        buildExitCode != null ? buildExitCode.intValue() : -1);
            }

            if (hasTestbench) {
                String runCmd = "cd /workspace && ./obj_dir/V" + topModule;

                com.github.dockerjava.api.command.CreateContainerResponse runContainer = dockerClient.createContainerCmd(VERILATOR_IMAGE)
                        .withName(containerName + "-run")
                        .withCmd("sh", "-c", runCmd)
                        .withHostConfig(com.github.dockerjava.api.model.HostConfig.newHostConfig()
                                .withBinds(com.github.dockerjava.api.model.Bind.parse(jobDir.toAbsolutePath() + ":/workspace"))
                                .withMemory(MAX_MEMORY_BYTES)
                                .withCpuCount(CPU_LIMIT)
                                .withNetworkMode("none")
                                .withReadonlyRootfs(true))
                        .withVolumes(new com.github.dockerjava.api.model.Volume("/workspace"))
                        .withWorkingDir("/workspace")
                        .exec();

                dockerClient.startContainerCmd(runContainer.getId()).exec();

                WaitContainerResultCallback simCallback = new WaitContainerResultCallback();
                dockerClient.waitContainerCmd(runContainer.getId()).exec(simCallback);

                boolean simCompleted = simCallback.awaitCompletion(60, TimeUnit.SECONDS);

                if (!simCompleted) {
                    dockerClient.killContainerCmd(runContainer.getId()).exec();

                    WaitContainerResultCallback simKillCallback = new WaitContainerResultCallback();
                    dockerClient.waitContainerCmd(runContainer.getId()).exec(simKillCallback);
                    simKillCallback.awaitCompletion(5, TimeUnit.SECONDS);

                    return new ContainerResult(false,
                            buildLogs + "\n=== SIMULATION TIMED OUT ===", -1);
                }

                String simLogs = dockerClient.logContainerCmd(runContainer.getId())
                        .withStdOut(true).withStdErr(true)
                        .exec(new LogToStringCallback()).toString();
                Long simExitCode = dockerClient.inspectContainerCmd(runContainer.getId()).exec().getState().getExitCodeLong();

                dockerClient.removeContainerCmd(runContainer.getId()).withForce(true).exec();

                return new ContainerResult(simExitCode != null && (simExitCode == 0 || simExitCode == 127),
                        buildLogs + "\n=== SIMULATION OUTPUT ===\n" + simLogs,
                        simExitCode != null ? simExitCode.intValue() : -1);
            }

            return new ContainerResult(true, buildLogs, 0);

        } catch (Exception e) {
            return new ContainerResult(false, "Container error: " + e.getMessage(), -1);
        } finally {
            try {
                dockerClient.removeContainerCmd(containerName + "-build").withForce(true).exec();
            } catch (Exception ignored) {}
            try {
                dockerClient.removeContainerCmd(containerName + "-run").withForce(true).exec();
            } catch (Exception ignored) {}
        }
    }

    private void deleteRecursively(Path path) {
        try (Stream<Path> stream = Files.walk(path)) {
            stream
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete file during cleanup: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.error("Error during recursive deletion of {}", path, e);
        }
    }

    private static class LogToStringCallback extends ResultCallbackTemplate<LogToStringCallback, Frame> {
        private final StringBuilder output = new StringBuilder();
        private final Object lock = new Object();

        @Override
        public void onNext(Frame item) {
            synchronized (lock) {
                output.append(new String(item.getPayload()));
            }
        }

        @Override
        public String toString() {
            synchronized (lock) {
                return output.toString();
            }
        }
    }

    private boolean detectUvmUsage(SimulationRequest request) {
        if (request.testbenchCode() == null || request.testbenchCode().trim().isEmpty()) {
            return false;
        }

        String testbench = request.testbenchCode().toLowerCase();
        return testbench.contains("uvm_pkg") ||
                testbench.contains("uvm_macros") ||
                testbench.contains("`uvm") ||
                testbench.contains("import uvm_pkg");
    }

    public Path createZipFromDirectory(Path directory, String prefix) throws IOException {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Directory does not exist: " + directory);
        }

        Path zipFile = Files.createTempFile(prefix, ".zip");

        try (ZipOutputStream zipOs = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            Files.walk(directory)
                    .filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
                        try {
                            String relativePath = directory.relativize(path).toString();
                            ZipEntry zipEntry = new ZipEntry(relativePath);
                            zipOs.putNextEntry(zipEntry);
                            Files.copy(path, zipOs);
                            zipOs.closeEntry();
                        } catch (IOException e) {
                            throw new UncheckedIOException("Failed to zip file: " + path, e);
                        }
                    });
        } catch (UncheckedIOException e) {
            Files.deleteIfExists(zipFile);
            throw e.getCause();
        }

        return zipFile;
    }

    @Transactional(readOnly = true)
    public Optional<Path> validateJobForDownload(String jobId) {
        Optional<SimulationJob> jobOpt = jobRepository.findByJobId(jobId);

        if (jobOpt.isEmpty()) {
            return Optional.empty();
        }

        SimulationJob job = jobOpt.get();

        if (job.isCleanedUp()) {
            log.debug("Job {} is already cleaned up", jobId);
            return Optional.empty();
        }

        Path jobDir = Paths.get(job.getDirectoryPath());
        if (!Files.exists(jobDir)) {
            log.warn("Job directory missing for job {}: {}", jobId, jobDir);
            return Optional.empty();
        }

        return Optional.of(jobDir);
    }

    @Transactional(readOnly = true)
    public Optional<Path> getModelDirectory(String jobId) {
        Optional<Path> jobDirOpt = validateJobForDownload(jobId);

        if (jobDirOpt.isEmpty()) {
            return Optional.empty();
        }

        Path jobDir = jobDirOpt.get();
        Path modelDir = jobDir.resolve("model_out");

        if (!Files.exists(modelDir)) {
            modelDir = jobDir.resolve("obj_dir");
            if (!Files.exists(modelDir)) {
                return Optional.empty();
            }
        }

        return Optional.of(modelDir);
    }

    public void scheduleTempFileCleanup(Path file, long delay, TimeUnit unit) {
        cleanupExecutor.schedule(() -> {
            try {
                Files.deleteIfExists(file);
                log.debug("Cleaned up temporary file: {}", file);
            } catch (IOException e) {
                log.warn("Failed to delete temp file: {}", file, e);
            }
        }, delay, unit);
    }
}
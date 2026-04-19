package app.verirun.service;

import app.verirun.dto.SimulationRequest;
import app.verirun.entity.SimulationJob;
import app.verirun.repository.SimulationJobRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class SimulationService {
    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final SimulationJobRepository jobRepository;
    private final ScheduledExecutorService tempFileCleanupExecutor;
    private static final String WORKSPACE_BASE = System.getProperty("java.io.tmpdir") + "/verirun";

    public SimulationService(SimulationJobRepository jobRepository) {
        this.jobRepository = jobRepository;
        this.tempFileCleanupExecutor = Executors.newScheduledThreadPool(1);
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down temp file cleanup executor");
        tempFileCleanupExecutor.shutdown();
        try {
            if (!tempFileCleanupExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                tempFileCleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            tempFileCleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }


    public String createSimulationJob(SimulationRequest request) throws IOException {
        String jobId = UUID.randomUUID().toString();
        Path jobDir = createJobDirectory(jobId);

        SimulationJob job = new SimulationJob(jobId, jobDir.toString(), request.resolvedOptions());

        Files.write(jobDir.resolve("design.sv"), request.designCode().getBytes());
        if (request.testbenchCode() != null && !request.testbenchCode().trim().isEmpty()) {
            Files.write(jobDir.resolve("testbench.sv"), request.testbenchCode().getBytes());
        }

        jobRepository.save(job);

        log.info("Created simulation job: {}", jobId);
        return jobId;
    }

    private Path createJobDirectory(String jobId) throws IOException {
        Path baseDir = Paths.get(WORKSPACE_BASE);

        if (!Files.exists(baseDir)) {
            Files.createDirectories(baseDir);
            log.debug("Created base directory: {}", baseDir);
        }

        Path jobDir = baseDir.resolve(jobId).normalize();
        Path realBaseDir = baseDir.toRealPath();

        if (!jobDir.startsWith(realBaseDir)) {
            throw new SecurityException("Invalid job directory path: escapes workspace base");
        }

        Files.createDirectories(jobDir);

        log.debug("Created job directory: {}", jobDir);
        return jobDir;
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
    public String getJobDirectoryPath(String jobId) {
        SimulationJob job = jobRepository.findByJobId(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        return job.getDirectoryPath();
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

    @Transactional(readOnly = true)
    public Optional<Path> getWaveformFiles(String jobId) throws IOException {
        Optional<Path> jobDirOpt = validateJobForDownload(jobId);
        if (jobDirOpt.isEmpty()) {
            return Optional.empty();
        }

        Path jobDir = jobDirOpt.get();
        List<Path> fstFiles;

        try (var stream = Files.walk(jobDir, 2)) {
            fstFiles = stream
                    .filter(p -> p.toString().endsWith(".fst") || p.toString().endsWith(".vcd"))
                    .toList();
        }

        if (fstFiles.isEmpty()) {
            return Optional.empty();
        }

        if (fstFiles.size() == 1) {
            return Optional.of(fstFiles.get(0));
        }

        Path zipFile = Files.createTempFile("waveform_" + jobId, ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (Path fst : fstFiles) {
                ZipEntry entry = new ZipEntry(jobDir.relativize(fst).toString());
                zos.putNextEntry(entry);
                Files.copy(fst, zos);
                zos.closeEntry();
            }
        }
        scheduleTempFileCleanup(zipFile, 5, TimeUnit.MINUTES);
        return Optional.of(zipFile);
    }

    public void scheduleTempFileCleanup(Path file, long delay, TimeUnit unit) {
        tempFileCleanupExecutor.schedule(() -> {
            try {
                Files.deleteIfExists(file);
                log.debug("Cleaned up temporary file: {}", file);
            } catch (IOException e) {
                log.warn("Failed to delete temp file: {}", file, e);
            }
        }, delay, unit);
    }
}

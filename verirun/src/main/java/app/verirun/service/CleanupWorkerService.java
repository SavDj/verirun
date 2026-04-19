package app.verirun.service;

import app.verirun.entity.SimulationJob;
import app.verirun.repository.SimulationJobRepository;
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
import java.util.List;
import java.util.stream.Stream;

@Service
@Profile("worker")
public class CleanupWorkerService {
    private static final Logger log = LoggerFactory.getLogger(CleanupWorkerService.class);
    private static final String WORKSPACE_BASE = System.getProperty("java.io.tmpdir") + "/verirun";

    private final SimulationJobRepository jobRepository;

    public CleanupWorkerService(SimulationJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanupExpiredJobs() {
        List<SimulationJob> expiredJobs = jobRepository.findExpiredJobs(Instant.now());

        log.debug("Found {} expired jobs to clean up", expiredJobs.size());

        for (SimulationJob job : expiredJobs) {
            try {
                cleanup(job);
            } catch (Exception e) {
                log.error("Failed to cleanup job {}", job.getJobId(), e);
            }
        }
    }

    private void cleanup(SimulationJob job) throws IOException {
        Path jobDir = Paths.get(job.getDirectoryPath());

        if (!Files.exists(jobDir)) {
            job.setCleanedUp(true);
            jobRepository.save(job);
            return;
        }

        Path realJobDir = jobDir.toRealPath();
        Path realWorkspaceBase = Paths.get(WORKSPACE_BASE).toRealPath();
        if (!realJobDir.startsWith(realWorkspaceBase)) {
            throw new SecurityException("Invalid cleanup path: " + job.getDirectoryPath());
        }

        boolean deleted = deleteRecursively(jobDir);
        if (!deleted) {
            log.warn("Incomplete cleanup for job {}", job.getJobId());
            return;
        }
        
        log.info("Cleaned up job directory: {}", job.getJobId());

        job.setCleanedUp(true);
        jobRepository.save(job);
    }

    private boolean deleteRecursively(Path path) throws IOException {
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path p : stream.sorted((a, b) -> -a.compareTo(b)).toList()) {
                Files.delete(p);
            }
            return true;
        } catch (IOException e) {
            log.warn("Recursive deletion failed for {}", path, e);
            return false;
        }
    }
}

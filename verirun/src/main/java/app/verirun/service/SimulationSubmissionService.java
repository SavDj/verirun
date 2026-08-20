package app.verirun.service;

import app.verirun.dto.SimulationRequest;
import app.verirun.entity.SimulationJob;
import app.verirun.entity.User;
import app.verirun.repository.SimulationJobRepository;
import app.verirun.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class SimulationSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SimulationSubmissionService.class);

    private final SimulationJobRepository jobRepository;
    private final UserRepository userRepository;
    private final VerilogSanitizerService sanitizer;
    private final Path workspaceBasePath;

    public SimulationSubmissionService(
            SimulationJobRepository jobRepository,
            UserRepository userRepository,
            VerilogSanitizerService sanitizer,
            @Value("${app.workspace.base-path:./verirun-workspace}") String workspaceBasePath) {
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
        this.sanitizer = sanitizer;
        this.workspaceBasePath = Paths.get(workspaceBasePath);
    }

    public String createSimulationJob(SimulationRequest request, UUID userId) throws IOException {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + userId));

        sanitizer.sanitize(request.designCode());
        if (request.testbenchCode() != null) {
            sanitizer.sanitize(request.testbenchCode());
        }

        String jobId = UUID.randomUUID().toString();
        Path jobDir = createJobDirectory(jobId);

        try {
            SimulationJob job = new SimulationJob(jobId, jobDir.toString(), request.resolvedOptions(), owner);

            Files.write(jobDir.resolve("design.sv"), request.designCode().getBytes(StandardCharsets.UTF_8));
            if (request.testbenchCode() != null && !request.testbenchCode().isBlank()) {
                Files.write(jobDir.resolve("testbench.sv"), request.testbenchCode().getBytes(StandardCharsets.UTF_8));
            }

            jobRepository.save(job);

            log.info("Created local scratchpad for job: {}", jobId);
            return jobId;
        } catch (Exception e) {
            log.error("Failed to create simulation job {}, cleaning up orphaned scratchpad", jobId, e);
            FileSystemUtils.deleteRecursively(jobDir);
            throw e;
        }
    }

    private Path createJobDirectory(String jobId) throws IOException {
        if (!Files.exists(workspaceBasePath)) {
            Files.createDirectories(workspaceBasePath);
            log.debug("Created base workspace directory: {}", workspaceBasePath);
        }

        Path realBaseDir = workspaceBasePath.toRealPath();
        Path jobDir = realBaseDir.resolve(jobId).normalize();

        if (!jobDir.startsWith(realBaseDir)) {
            throw new SecurityException("Invalid job directory path: escapes workspace base");
        }

        Files.createDirectories(jobDir);
        return jobDir;
    }
}

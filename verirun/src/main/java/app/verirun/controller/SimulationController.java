package app.verirun.controller;

import app.verirun.dto.SimulationRequest;
import app.verirun.entity.SimulationJob;
import app.verirun.repository.SimulationJobRepository;
import app.verirun.service.JobQueueService;
import app.verirun.service.SimulationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1")
public class SimulationController {
    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);

    private final SimulationService simulationService;
    private final SimulationJobRepository jobRepository;
    private final JobQueueService jobQueueService;

    public SimulationController(SimulationService simulationService, 
                                SimulationJobRepository jobRepository,
                                JobQueueService jobQueueService) {
        this.simulationService = simulationService;
        this.jobRepository = jobRepository;
        this.jobQueueService = jobQueueService;
    }

    @PreAuthorize("hasRole('ROLE_REGISTERED_USER')")
    @PostMapping("/simulate")
    public ResponseEntity<?> runSimulation(@RequestBody SimulationRequest request) throws IOException {
        if (request.designCode() == null || request.designCode().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Design code is required");
        }
        if (request.designCode().length() > 100_000 ||
                (request.testbenchCode() != null && request.testbenchCode().length() > 100_000)) {
            return ResponseEntity.badRequest().body("Code too large (max 100KB per file)");
        }

        String jobId = simulationService.createSimulationJob(request);
        jobQueueService.enqueueJob(jobId);

        return ResponseEntity.ok(Map.of(
                "jobId", jobId,
                "status", "PENDING"
        ));
    }

    @PreAuthorize("hasRole('ROLE_REGISTERED_USER')")
    @GetMapping("/jobs/{jobId}/status")
    public ResponseEntity<?> getJobStatus(@PathVariable String jobId) {
        Optional<SimulationJob> jobOpt = jobRepository.findByJobId(jobId);
        
        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        SimulationJob job = jobOpt.get();

        Map<String, Object> response = new HashMap<>();
        response.put("jobId", job.getJobId());
        response.put("status", job.getStatus().name());
        response.put("createdAt", job.getCreatedAt().toString());
        response.put("startedAt", job.getStartedAt() != null ? job.getStartedAt().toString() : null);
        response.put("completedAt", job.getCompletedAt() != null ? job.getCompletedAt().toString() : null);
        response.put("errorMessage", job.getErrorMessage());
        response.put("retryCount", job.getRetryCount());

        if (job.getStatus() == SimulationJob.JobStatus.COMPLETED && job.getResultJson() != null) {
            response = new HashMap<>();
            response.put("jobId", job.getJobId());
            response.put("status", job.getStatus().name());
            response.put("createdAt", job.getCreatedAt().toString());
            response.put("startedAt", job.getStartedAt() != null ? job.getStartedAt().toString() : null);
            response.put("completedAt", job.getCompletedAt() != null ? job.getCompletedAt().toString() : null);
            response.put("result", job.getResultJson());
            response.put("buildMode", job.getVerilatorOptions().buildMode().name());
        }

        return ResponseEntity.ok(response);
    }

    @PreAuthorize("hasRole('ROLE_REGISTERED_USER')")
    @GetMapping("/simulate/download/{jobId}")
    public ResponseEntity<Resource> downloadModel(@PathVariable String jobId) throws IOException {
        try {
            Optional<Path> modelDirOpt = simulationService.getModelDirectory(jobId);

            if (modelDirOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Path modelDir = modelDirOpt.get();

            Path zipFile = simulationService.createZipFromDirectory(modelDir, "verilator_model_" + jobId);

            simulationService.scheduleTempFileCleanup(zipFile, 5, TimeUnit.MINUTES);

            Resource resource = new FileSystemResource(zipFile);

            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"verilator_model_" + jobId + ".zip\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(resource.contentLength())
                    .body(resource);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PreAuthorize("hasRole('ROLE_REGISTERED_USER')")
    @GetMapping("/simulate/download-waveform/{jobId}")
    public ResponseEntity<Resource> downloadWaveform(@PathVariable String jobId) throws IOException {
        try {
            Optional<Path> waveformOpt = simulationService.getWaveformFiles(jobId);

            if (waveformOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Path waveformPath = waveformOpt.get();

            Path jobDir = Paths.get(simulationService.getJobDirectoryPath(jobId));
            if (!waveformPath.toAbsolutePath().normalize().startsWith(jobDir.toAbsolutePath().normalize())) {
                log.warn("Attempted path traversal for job {}: {}", jobId, waveformPath);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            Resource resource = new FileSystemResource(waveformPath);

            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            String filename = determineFilename(waveformPath, jobId);
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                    .replace("+", "%20");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(resource.contentLength())
                    .body(resource);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid waveform path for job {}", jobId, e);
            return ResponseEntity.notFound().build();
        }
    }

    private String determineFilename(Path path, String jobId) {
        String name = path.getFileName().toString();
        if (name.endsWith(".fst") || name.endsWith(".vcd") || name.endsWith(".zip")) {
            return "verirun_" + jobId + "_" + name;
        }
        return "verirun_" + jobId + "_waveform.zip";
    }

}

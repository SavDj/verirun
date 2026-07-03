package app.verirun.controller;

import app.verirun.dto.JobStatusResponse;
import app.verirun.dto.SimulationRequest;
import app.verirun.dto.SimulationSubmissionResponse;
import app.verirun.service.JobQueueService;
import app.verirun.service.SimulationQueryService;
import app.verirun.service.SimulationSubmissionService;
import app.verirun.storage.ArtifactStorageService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1")
public class SimulationController {

    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);

    private final SimulationSubmissionService simulationSubmissionService;
    private final SimulationQueryService simulationQueryService;
    private final JobQueueService jobQueueService;
    private final ArtifactStorageService storageService;

    public SimulationController(SimulationSubmissionService simulationSubmissionService,
                                SimulationQueryService simulationQueryService,
                                JobQueueService jobQueueService,
                                ArtifactStorageService storageService) {
        this.simulationSubmissionService = simulationSubmissionService;
        this.simulationQueryService = simulationQueryService;
        this.jobQueueService = jobQueueService;
        this.storageService = storageService;
    }

    @PreAuthorize("hasRole('REGISTERED_USER')")
    @PostMapping("/simulate")
    public ResponseEntity<SimulationSubmissionResponse> runSimulation(@Valid @RequestBody SimulationRequest request) throws IOException {
        String jobId = simulationSubmissionService.createSimulationJob(request);
        jobQueueService.enqueueJob(jobId);

        return ResponseEntity.ok(new SimulationSubmissionResponse(jobId, "PENDING"));
    }

    @PreAuthorize("hasRole('REGISTERED_USER')")
    @GetMapping("/jobs/{jobId}/status")
    public ResponseEntity<JobStatusResponse> getJobStatus(@PathVariable String jobId) {
        return simulationQueryService.getJobStatus(jobId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('REGISTERED_USER')")
    @GetMapping("/simulate/download/{jobId}")
    public ResponseEntity<Resource> downloadModel(@PathVariable String jobId) {
        return downloadArtifact(jobId, "model.zip", "verilator_model_" + jobId + ".zip");
    }

    @PreAuthorize("hasRole('REGISTERED_USER')")
    @GetMapping("/simulate/download-waveform/{jobId}")
    public ResponseEntity<Resource> downloadWaveform(@PathVariable String jobId) {
        return downloadArtifact(jobId, "waveform.vcd", "verirun_" + jobId + "_waveform.vcd");
    }

    private ResponseEntity<Resource> downloadArtifact(String jobId, String fileName, String downloadName) {
        try {
            Resource s3Resource = storageService.downloadArtifact(jobId, fileName);

            if (s3Resource == null || !s3Resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(s3Resource);

        } catch (Exception e) {
            log.error("Error downloading {} from S3 for job {}", fileName, jobId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
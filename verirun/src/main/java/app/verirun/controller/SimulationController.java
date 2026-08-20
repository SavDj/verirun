package app.verirun.controller;

import app.verirun.dto.JobStatusResponse;
import app.verirun.dto.SimulationRequest;
import app.verirun.dto.SimulationSubmissionResponse;
import app.verirun.security.UserDetailsImpl;
import app.verirun.service.JobQueueService;
import app.verirun.service.SimulationArtifactService;
import app.verirun.service.SimulationQueryService;
import app.verirun.service.SimulationSubmissionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SimulationController {

    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);

    private final SimulationSubmissionService simulationSubmissionService;
    private final SimulationQueryService simulationQueryService;
    private final JobQueueService jobQueueService;
    private final SimulationArtifactService simulationArtifactService;

    public SimulationController(SimulationSubmissionService simulationSubmissionService,
                                SimulationQueryService simulationQueryService,
                                JobQueueService jobQueueService,
                                SimulationArtifactService simulationArtifactService) {
        this.simulationSubmissionService = simulationSubmissionService;
        this.simulationQueryService = simulationQueryService;
        this.jobQueueService = jobQueueService;
        this.simulationArtifactService = simulationArtifactService;
    }

    @PreAuthorize("hasRole('REGISTERED_USER')")
    @PostMapping("/simulate")
    public ResponseEntity<SimulationSubmissionResponse> runSimulation(
            @Valid @RequestBody SimulationRequest request,
            @AuthenticationPrincipal UserDetailsImpl principal) throws IOException {
        String jobId = simulationSubmissionService.createSimulationJob(request, principal.getId());
        jobQueueService.enqueueJob(jobId);

        return ResponseEntity.ok(new SimulationSubmissionResponse(jobId, "PENDING"));
    }

    @PreAuthorize("hasRole('REGISTERED_USER')")
    @GetMapping("/jobs/{jobId}/status")
    public ResponseEntity<JobStatusResponse> getJobStatus(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return simulationQueryService.getJobStatus(jobId, principal.getId())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('REGISTERED_USER')")
    @GetMapping("/simulate/download/{jobId}")
    public ResponseEntity<Resource> downloadModel(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return downloadArtifact(jobId, principal.getId(), "model.zip", "verilator_model_" + jobId + ".zip");
    }

    @PreAuthorize("hasRole('REGISTERED_USER')")
    @GetMapping("/simulate/download-waveform/{jobId}")
    public ResponseEntity<Resource> downloadWaveform(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return downloadArtifact(jobId, principal.getId(), "waveform.vcd", "verirun_" + jobId + "_waveform.vcd");
    }

    private ResponseEntity<Resource> downloadArtifact(String jobId, UUID userId,
                                                      String fileName, String downloadName) {
        try {
            Optional<Resource> artifact = simulationArtifactService.downloadArtifact(jobId, userId, fileName);
            if (artifact.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Resource s3Resource = artifact.get();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(s3Resource);

        } catch (Exception e) {
            log.error("Error downloading artifact {} for job {}", fileName, jobId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}

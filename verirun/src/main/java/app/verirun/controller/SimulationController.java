package app.verirun.controller;

import app.verirun.dto.JobStatusResponse;
import app.verirun.dto.SimulationRequest;
import app.verirun.dto.SimulationSubmissionResponse;
import app.verirun.security.UserDetailsImpl;
import app.verirun.service.SimulationArtifactService;
import app.verirun.service.SimulationQueryService;
import app.verirun.service.SimulationSubmissionService;
import app.verirun.storage.SimulationStorageService.Output;
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

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SimulationController {

    private final SimulationSubmissionService simulationSubmissionService;
    private final SimulationQueryService simulationQueryService;
    private final SimulationArtifactService simulationArtifactService;

    public SimulationController(SimulationSubmissionService simulationSubmissionService, SimulationQueryService simulationQueryService, SimulationArtifactService simulationArtifactService) {
        this.simulationSubmissionService = simulationSubmissionService;
        this.simulationQueryService = simulationQueryService;
        this.simulationArtifactService = simulationArtifactService;
    }

    @PreAuthorize("hasRole('REGISTERED_USER')")
    @PostMapping("/simulate")
    public ResponseEntity<SimulationSubmissionResponse> runSimulation(@Valid @RequestBody SimulationRequest request, @AuthenticationPrincipal UserDetailsImpl principal) {
        String jobId = simulationSubmissionService.submitSimulation(request, principal.getId());

        return ResponseEntity.ok(new SimulationSubmissionResponse(jobId, "PENDING"));
    }

    @PreAuthorize("hasRole('REGISTERED_USER')")
    @GetMapping("/jobs/{jobId}/status")
    public ResponseEntity<JobStatusResponse> getJobStatus(@PathVariable String jobId, @AuthenticationPrincipal UserDetailsImpl principal) {
        return simulationQueryService.getJobStatus(jobId, principal.getId()).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('REGISTERED_USER')")
    @GetMapping("/simulate/download/{jobId}")
    public ResponseEntity<Resource> downloadModel(@PathVariable String jobId, @AuthenticationPrincipal UserDetailsImpl principal) {
        return downloadArtifact(jobId, principal.getId(), Output.MODEL, "verilator_model_" + jobId + ".zip");
    }

    @PreAuthorize("hasRole('REGISTERED_USER')")
    @GetMapping("/simulate/download-waveform/{jobId}")
    public ResponseEntity<Resource> downloadWaveform(@PathVariable String jobId, @AuthenticationPrincipal UserDetailsImpl principal) {
        return downloadArtifact(jobId, principal.getId(), Output.WAVEFORM, "verirun_" + jobId + "_waveform.vcd");
    }

    private ResponseEntity<Resource> downloadArtifact(String jobId, UUID userId, Output output, String downloadName) {
        Optional<Resource> artifact = simulationArtifactService.downloadArtifact(jobId, userId, output);

        if (artifact.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"").
                header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600").contentType(MediaType.APPLICATION_OCTET_STREAM).body(artifact.get());
    }
}

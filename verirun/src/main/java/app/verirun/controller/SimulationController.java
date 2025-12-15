package app.verirun.controller;

import app.verirun.dto.SimulationRequest;
import app.verirun.dto.SimulationResult;
import app.verirun.repository.SimulationJobRepository;
import app.verirun.service.SimulationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/v1")
public class SimulationController {

    private final SimulationService simulationService;
    private final ExecutorService executor = Executors.newFixedThreadPool(5);

    @Autowired
    public SimulationController(SimulationService simulationService, SimulationJobRepository jobRepository) {
        this.simulationService = simulationService;
    }

    @PreAuthorize("hasRole('ROLE_REGISTERED_USER')")
    @PostMapping("/simulate")
    public ResponseEntity<?> runSimulation(@RequestBody SimulationRequest request) {
        if (request.designCode() == null || request.designCode().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Design code is required");
        }
        if (request.designCode().length() > 100_000 || request.testbenchCode().length() > 100_000) {
            return ResponseEntity.badRequest().body("Code too large (max 100KB per file)");
        }

        try {
            Future<SimulationResult> future = executor.submit(() ->
                    simulationService.runSimulation(request)
            );

            SimulationResult result = future.get(150, TimeUnit.SECONDS);

            return ResponseEntity.ok(Map.of(
                    "passed", result.passed(),
                    "logs", result.logs(),
                    "jobId", result.jobId()
            ));

        } catch (TimeoutException e) {
            return ResponseEntity.status(408).body("Simulation timed out");
        } catch (ExecutionException e) {
            return ResponseEntity.status(500).body("Simulation failed: " + e.getCause().getMessage());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid request: " + e.getMessage());
        }
    }

    @PreAuthorize("hasRole('ROLE_REGISTERED_USER')")
    @GetMapping("/simulate/download/{jobId}")
    public ResponseEntity<Resource> downloadModel(@PathVariable String jobId) {
        try {
            Optional<Path> modelDirOpt = simulationService.getModelDirectory(jobId);

            if (modelDirOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Path modelDir = modelDirOpt.get();

            Path zipFile = simulationService.createZipFromDirectory(modelDir, "verilator_model_" + jobId);

            simulationService.scheduleTempFileCleanup(zipFile, 5, TimeUnit.MINUTES);

            Resource resource = new UrlResource(zipFile.toUri());

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
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

}
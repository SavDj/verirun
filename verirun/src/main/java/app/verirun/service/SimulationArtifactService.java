package app.verirun.service;

import app.verirun.entity.SimulationJob;
import app.verirun.repository.SimulationJobRepository;
import app.verirun.storage.SimulationStorageService;
import app.verirun.storage.SimulationStorageService.Output;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class SimulationArtifactService {

    private final SimulationJobRepository jobRepository;
    private final SimulationStorageService storageService;

    public SimulationArtifactService(SimulationJobRepository jobRepository, SimulationStorageService storageService) {
        this.jobRepository = jobRepository;
        this.storageService = storageService;
    }

    public Optional<Resource> downloadArtifact(String jobId, UUID userId, Output output) {

        if (!jobRepository.existsByJobIdAndOwner_Id(jobId, userId)) {
            return Optional.empty();
        }

        Resource resource = storageService.downloadOutput(UUID.fromString(jobId), output);

        return resource.exists() ? Optional.of(resource) : Optional.empty();
    }
}
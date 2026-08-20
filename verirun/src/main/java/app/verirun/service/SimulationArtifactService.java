package app.verirun.service;

import app.verirun.repository.SimulationJobRepository;
import app.verirun.storage.ArtifactStorageService;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class SimulationArtifactService {

    private final SimulationJobRepository jobRepository;
    private final ArtifactStorageService storageService;

    public SimulationArtifactService(SimulationJobRepository jobRepository,
                                     ArtifactStorageService storageService) {
        this.jobRepository = jobRepository;
        this.storageService = storageService;
    }

    public Optional<Resource> downloadArtifact(String jobId, UUID userId, String fileName) {
        if (!jobRepository.existsByJobIdAndOwner_Id(jobId, userId)) {
            return Optional.empty();
        }

        Resource resource = storageService.downloadArtifact(jobId, fileName);
        if (resource == null || !resource.exists()) {
            return Optional.empty();
        }
        return Optional.of(resource);
    }
}

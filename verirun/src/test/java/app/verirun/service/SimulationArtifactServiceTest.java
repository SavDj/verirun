package app.verirun.service;

import app.verirun.repository.SimulationJobRepository;
import app.verirun.storage.SimulationStorageService;
import app.verirun.storage.SimulationStorageService.Output;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationArtifactServiceTest {

    private static final UUID JOB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private SimulationJobRepository jobRepository;

    @Mock
    private SimulationStorageService storageService;

    @Mock
    private Resource resource;

    private final UUID userId = UUID.randomUUID();
    private SimulationArtifactService artifactService;

    @BeforeEach
    void setUp() {
        artifactService = new SimulationArtifactService(jobRepository, storageService);
    }

    @Test
    void downloadArtifact_shouldReturnOwnedModelArtifact() {
        when(jobRepository.existsByJobIdAndOwner_Id(JOB_ID.toString(), userId)).thenReturn(true);
        when(storageService.downloadOutput(JOB_ID, Output.MODEL)).thenReturn(resource);
        when(resource.exists()).thenReturn(true);

        Optional<Resource> result = artifactService.downloadArtifact(JOB_ID.toString(), userId, Output.MODEL);

        assertThat(result).containsSame(resource);
    }

    @Test
    void downloadArtifact_shouldNotAccessStorageWhenJobIsUnownedOrMissing() {
        when(jobRepository.existsByJobIdAndOwner_Id(JOB_ID.toString(), userId)).thenReturn(false);

        Optional<Resource> result = artifactService.downloadArtifact(JOB_ID.toString(), userId, Output.MODEL);

        assertThat(result).isEmpty();

        verify(jobRepository).existsByJobIdAndOwner_Id(JOB_ID.toString(), userId);
        verifyNoInteractions(storageService);
    }

    @Test
    void downloadArtifact_shouldReturnEmptyWhenOwnedWaveformIsMissing() {
        when(jobRepository.existsByJobIdAndOwner_Id(JOB_ID.toString(), userId)).thenReturn(true);
        when(storageService.downloadOutput(JOB_ID, Output.WAVEFORM)).thenReturn(resource);
        when(resource.exists()).thenReturn(false);

        Optional<Resource> result = artifactService.downloadArtifact(JOB_ID.toString(), userId, Output.WAVEFORM);

        assertThat(result).isEmpty();

        verify(jobRepository).existsByJobIdAndOwner_Id(JOB_ID.toString(), userId);
        verify(storageService).downloadOutput(JOB_ID, Output.WAVEFORM);
        verify(resource).exists();
    }
}

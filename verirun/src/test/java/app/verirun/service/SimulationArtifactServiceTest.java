package app.verirun.service;

import app.verirun.repository.SimulationJobRepository;
import app.verirun.storage.ArtifactStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationArtifactServiceTest {

    @Mock
    private SimulationJobRepository jobRepository;

    @Mock
    private ArtifactStorageService storageService;

    @Mock
    private Resource resource;

    private final UUID userId = UUID.randomUUID();
    private SimulationArtifactService artifactService;

    @BeforeEach
    void setUp() {
        artifactService = new SimulationArtifactService(jobRepository, storageService);
    }

    @Test
    void downloadArtifact_shouldDownloadOwnedModelArtifact() {
        when(jobRepository.existsByJobIdAndOwner_Id("job-123", userId)).thenReturn(true);
        when(storageService.downloadArtifact("job-123", "model.zip")).thenReturn(resource);
        when(resource.exists()).thenReturn(true);

        Optional<Resource> result = artifactService.downloadArtifact("job-123", userId, "model.zip");

        assertThat(result).containsSame(resource);
        verify(jobRepository).existsByJobIdAndOwner_Id("job-123", userId);
        verify(storageService).downloadArtifact("job-123", "model.zip");
        verify(resource).exists();
        verify(jobRepository, never()).findByJobIdAndOwner_Id(anyString(), eq(userId));
        verify(jobRepository, never()).findByJobId(anyString());
    }

    @Test
    void downloadArtifact_shouldDownloadOwnedWaveformArtifact() {
        when(jobRepository.existsByJobIdAndOwner_Id("job-123", userId)).thenReturn(true);
        when(storageService.downloadArtifact("job-123", "waveform.vcd")).thenReturn(resource);
        when(resource.exists()).thenReturn(true);

        Optional<Resource> result = artifactService.downloadArtifact("job-123", userId, "waveform.vcd");

        assertThat(result).containsSame(resource);
        verify(jobRepository).existsByJobIdAndOwner_Id("job-123", userId);
        verify(storageService).downloadArtifact("job-123", "waveform.vcd");
        verify(resource).exists();
    }

    @Test
    void downloadArtifact_shouldNotTouchStorageForAnotherUsersModel() {
        when(jobRepository.existsByJobIdAndOwner_Id("job-123", userId)).thenReturn(false);

        Optional<Resource> result = artifactService.downloadArtifact("job-123", userId, "model.zip");

        assertThat(result).isEmpty();
        verify(jobRepository).existsByJobIdAndOwner_Id("job-123", userId);
        verifyNoInteractions(storageService, resource);
        verify(jobRepository, never()).findByJobIdAndOwner_Id(anyString(), eq(userId));
        verify(jobRepository, never()).findByJobId(anyString());
    }

    @Test
    void downloadArtifact_shouldNotTouchStorageForAnotherUsersWaveform() {
        when(jobRepository.existsByJobIdAndOwner_Id("job-123", userId)).thenReturn(false);

        Optional<Resource> result = artifactService.downloadArtifact("job-123", userId, "waveform.vcd");

        assertThat(result).isEmpty();
        verify(jobRepository).existsByJobIdAndOwner_Id("job-123", userId);
        verifyNoInteractions(storageService, resource);
    }

    @Test
    void downloadArtifact_shouldNotTouchStorageForMissingJob() {
        when(jobRepository.existsByJobIdAndOwner_Id("missing-job", userId)).thenReturn(false);

        Optional<Resource> result = artifactService.downloadArtifact("missing-job", userId, "model.zip");

        assertThat(result).isEmpty();
        verify(jobRepository).existsByJobIdAndOwner_Id("missing-job", userId);
        verifyNoInteractions(storageService, resource);
    }

    @Test
    void downloadArtifact_shouldReturnEmptyWhenOwnedArtifactIsMissing() {
        when(jobRepository.existsByJobIdAndOwner_Id("job-123", userId)).thenReturn(true);
        when(storageService.downloadArtifact("job-123", "model.zip")).thenReturn(resource);
        when(resource.exists()).thenReturn(false);

        Optional<Resource> result = artifactService.downloadArtifact("job-123", userId, "model.zip");

        assertThat(result).isEmpty();
        verify(jobRepository).existsByJobIdAndOwner_Id("job-123", userId);
        verify(storageService).downloadArtifact("job-123", "model.zip");
        verify(resource).exists();
    }

    @Test
    void downloadArtifact_shouldPreserveStorageFailureAfterAuthorization() {
        when(jobRepository.existsByJobIdAndOwner_Id("job-123", userId)).thenReturn(true);
        when(storageService.downloadArtifact("job-123", "model.zip"))
                .thenThrow(new RuntimeException("storage unavailable"));

        assertThatThrownBy(() -> artifactService.downloadArtifact("job-123", userId, "model.zip"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("storage unavailable");

        verify(jobRepository).existsByJobIdAndOwner_Id("job-123", userId);
        verify(storageService).downloadArtifact("job-123", "model.zip");
        verifyNoInteractions(resource);
    }
}

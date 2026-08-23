package app.verirun.service;

import app.verirun.dto.SimulationRequest;
import app.verirun.entity.SimulationJob;
import app.verirun.entity.User;
import app.verirun.repository.SimulationJobRepository;
import app.verirun.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationSubmissionServiceTest {

    @Mock
    private SimulationJobRepository jobRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private VerilogSanitizerService sanitizer;

    private SimulationSubmissionService simulationService;

    private final UUID userId = UUID.randomUUID();
    private User owner;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        owner = new User("owner@verirun.com");
        owner.setId(userId);
        simulationService = new SimulationSubmissionService(jobRepository, userRepository, sanitizer, tempDir.toString());
    }

    @Test
    void createSimulationJob_shouldCreateDirectoryAndWriteFiles() throws Exception {
        String designCode = "module ALU(); endmodule";
        String tbCode = "module tb(); endmodule";

        SimulationRequest request = new SimulationRequest(designCode, tbCode, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));

        String jobId = simulationService.createSimulationJob(request, userId);

        ArgumentCaptor<SimulationJob> captor = ArgumentCaptor.forClass(SimulationJob.class);
        verify(jobRepository).save(captor.capture());
        SimulationJob savedJob = captor.getValue();

        assertThat(savedJob.getJobId()).isEqualTo(jobId);
        assertThat(savedJob.getOwner()).isSameAs(owner);
        assertThat(savedJob.getDirectoryPath()).isEqualTo(tempDir.resolve(jobId).toString());

        verify(userRepository).findById(eq(userId));

        Path jobDir = tempDir.resolve(jobId);
        assertThat(jobDir).exists().isDirectory();

        assertThat(Files.readString(jobDir.resolve("design.sv"))).isEqualTo(designCode);
        assertThat(Files.readString(jobDir.resolve("testbench.sv"))).isEqualTo(tbCode);
    }

    @Test
    void createSimulationJob_shouldNotWriteTestbench_whenCodeIsNull() throws Exception {
        String designCode = "module CPU(); endmodule";
        SimulationRequest request = new SimulationRequest(designCode, null, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));

        String jobId = simulationService.createSimulationJob(request, userId);

        Path jobDir = tempDir.resolve(jobId);
        assertThat(jobDir.resolve("design.sv")).exists();
        assertThat(jobDir.resolve("testbench.sv")).doesNotExist();
    }

    @Test
    void createSimulationJob_shouldRejectUnknownOwnerBeforeCreatingFiles() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        SimulationRequest request = new SimulationRequest("module CPU(); endmodule", null, null);

        assertThatThrownBy(() -> simulationService.createSimulationJob(request, userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Authenticated user not found");
    }
}

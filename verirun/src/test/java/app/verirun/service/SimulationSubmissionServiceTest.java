package app.verirun.service;

import app.verirun.dto.SimulationRequest;
import app.verirun.entity.SimulationJob;
import app.verirun.repository.SimulationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationSubmissionServiceTest {

    @Mock
    private SimulationJobRepository jobRepository;

    @Mock
    private VerilogSanitizerService sanitizer;

    private SimulationSubmissionService simulationService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        simulationService = new SimulationSubmissionService(jobRepository, sanitizer, tempDir.toString());
        when(jobRepository.save(any(SimulationJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createSimulationJob_shouldCreateDirectoryAndWriteFiles() throws Exception {
        String designCode = "module ALU(); endmodule";
        String tbCode = "module tb(); endmodule";

        SimulationRequest request = new SimulationRequest(designCode, tbCode, null);

        String jobId = simulationService.createSimulationJob(request);

        ArgumentCaptor<SimulationJob> captor = ArgumentCaptor.forClass(SimulationJob.class);
        verify(jobRepository).save(captor.capture());
        SimulationJob savedJob = captor.getValue();

        assertThat(savedJob.getJobId()).isEqualTo(jobId);
        assertThat(savedJob.getDirectoryPath()).isEqualTo(tempDir.resolve(jobId).toString());

        Path jobDir = tempDir.resolve(jobId);
        assertThat(jobDir).exists().isDirectory();

        assertThat(Files.readString(jobDir.resolve("design.sv"))).isEqualTo(designCode);
        assertThat(Files.readString(jobDir.resolve("testbench.sv"))).isEqualTo(tbCode);
    }

    @Test
    void createSimulationJob_shouldNotWriteTestbench_WhenCodeIsNull() throws Exception {
        String designCode = "module CPU(); endmodule";
        SimulationRequest request = new SimulationRequest(designCode, null, null);

        String jobId = simulationService.createSimulationJob(request);

        Path jobDir = tempDir.resolve(jobId);
        assertThat(jobDir.resolve("design.sv")).exists();
        assertThat(jobDir.resolve("testbench.sv")).doesNotExist();
    }
}
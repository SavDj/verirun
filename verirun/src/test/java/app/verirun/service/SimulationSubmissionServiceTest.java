package app.verirun.service;

import app.verirun.dto.SimulationRequest;
import app.verirun.dto.VerilatorOptions;
import app.verirun.dto.VerilatorOptions.BuildMode;
import app.verirun.dto.VerilatorOptions.OptimizationLevel;
import app.verirun.entity.SimulationJob;
import app.verirun.entity.User;
import app.verirun.exception.InvalidCodeException;
import app.verirun.repository.UserRepository;
import app.verirun.storage.SimulationStorageService;
import app.verirun.storage.SimulationStorageService.Input;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static app.verirun.service.JobQueueService.PublicationResult.CONFIRMED;
import static app.verirun.service.JobQueueService.PublicationResult.NOT_CONFIRMED;
import static app.verirun.service.SimulationJobPersistenceService.PersistenceOutcome.COMMITTED;
import static app.verirun.service.SimulationJobPersistenceService.PersistenceOutcome.COMMIT_AMBIGUOUS;
import static app.verirun.service.SimulationJobPersistenceService.PersistenceOutcome.DEFINITELY_NOT_CREATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationSubmissionServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private VerilogSanitizerService sanitizer;

    @Mock
    private SimulationStorageService storageService;

    @Mock
    private SimulationJobPersistenceService persistenceService;

    @Mock
    private JobQueueService jobQueueService;

    private final UUID userId = UUID.randomUUID();
    private User owner;
    private SimulationSubmissionService simulationService;

    @BeforeEach
    void setUp() {
        owner = new User("owner@verirun.com");
        owner.setId(userId);

        simulationService = new SimulationSubmissionService(userRepository, sanitizer, storageService, persistenceService, jobQueueService);
    }

    @Test
    void submitSimulation_shouldStoreExactInputsPersistJobAndPublishInOrder() {
        String design = "module café(); endmodule";
        String testbench = "module tb_λ(); endmodule";
        VerilatorOptions options = new VerilatorOptions(BuildMode.CC_MODEL, true, false, 4, true, true, 3, OptimizationLevel.O1,
                List.of("rtl"), List.of("SYNTHESIS"), List.of("WIDTH"), List.of("--x-assign=fast"), List.of("+seed=7"));

        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        when(persistenceService.persistNewJob(any())).thenReturn(COMMITTED);
        when(jobQueueService.enqueueJob(any())).thenReturn(CONFIRMED);

        String jobId = simulationService.submitSimulation(new SimulationRequest(design, testbench, options), userId);

        UUID durableJobId = UUID.fromString(jobId);
        ArgumentCaptor<SimulationJob> jobCaptor = ArgumentCaptor.forClass(SimulationJob.class);

        InOrder order = inOrder(storageService, persistenceService, jobQueueService);

        order.verify(storageService).writeInput(durableJobId, Input.DESIGN, design);
        order.verify(storageService).writeInput(durableJobId, Input.TESTBENCH, testbench);
        order.verify(persistenceService).persistNewJob(jobCaptor.capture());
        order.verify(jobQueueService).enqueueJob(jobId);

        SimulationJob persistedJob = jobCaptor.getValue();

        assertThat(persistedJob.getJobId()).isEqualTo(jobId);
        assertThat(persistedJob.isTestbenchExpected()).isTrue();
        assertThat(persistedJob.getOwner()).isSameAs(owner);
        assertThat(persistedJob.getVerilatorOptions()).isEqualTo(options);

        verify(storageService, never()).deleteInput(any(), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\r\n"})
    void submitSimulation_shouldStoreDesignOnlyWhenTestbenchIsAbsentOrBlank(String testbench) {

        String design = "module cpu; endmodule";

        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        when(persistenceService.persistNewJob(any())).thenReturn(COMMITTED);
        when(jobQueueService.enqueueJob(any())).thenReturn(CONFIRMED);

        simulationService.submitSimulation(new SimulationRequest(design, testbench, null), userId);

        verify(storageService).writeInput(any(UUID.class), eq(Input.DESIGN), eq(design));
        verify(storageService, never()).writeInput(any(UUID.class), eq(Input.TESTBENCH), any());

        ArgumentCaptor<SimulationJob> jobCaptor = ArgumentCaptor.forClass(SimulationJob.class);

        verify(persistenceService).persistNewJob(jobCaptor.capture());

        assertThat(jobCaptor.getValue().isTestbenchExpected()).isFalse();

        verify(storageService, never()).deleteInput(any(), any());
    }

    @Test
    void submitSimulation_shouldStopBeforeDurableSideEffectsWhenDesignIsRejected() {
        String design = "invalid";
        InvalidCodeException rejection = new InvalidCodeException("rejected");

        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        doThrow(rejection).when(sanitizer).sanitize(design);

        assertThatThrownBy(() -> simulationService.submitSimulation(new SimulationRequest(design, "module tb; endmodule", null), userId)).isInstanceOf(InvalidCodeException.class);

        verifyNoInteractions(storageService, persistenceService, jobQueueService);
    }

    @Test
    void submitSimulation_shouldStopBeforeDurableSideEffectsWhenTestbenchIsRejected() {
        String design = "module design; endmodule";
        String testbench = "invalid testbench";
        InvalidCodeException rejection = new InvalidCodeException("rejected");

        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        doNothing().when(sanitizer).sanitize(design);
        doThrow(rejection).when(sanitizer).sanitize(testbench);

        assertThatThrownBy(() -> simulationService.submitSimulation(new SimulationRequest(design, testbench, null), userId)).isInstanceOf(InvalidCodeException.class);

        verify(sanitizer).sanitize(design);
        verify(sanitizer).sanitize(testbench);

        verifyNoInteractions(storageService, persistenceService, jobQueueService);
    }

    @Test
    void submitSimulation_shouldCleanupEveryAttemptedInputWhenWriteFails() {
        String design = "module d; endmodule";
        String testbench = "module tb; endmodule";
        RuntimeException writeFailure = new RuntimeException("testbench write failed");

        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        doNothing().when(storageService).writeInput(any(UUID.class), eq(Input.DESIGN), eq(design));
        doThrow(writeFailure).when(storageService).writeInput(any(UUID.class), eq(Input.TESTBENCH), eq(testbench));

        assertThatThrownBy(() -> simulationService.submitSimulation(new SimulationRequest(design, testbench, null), userId)).isInstanceOf(RuntimeException.class);

        ArgumentCaptor<UUID> jobIdCaptor = ArgumentCaptor.forClass(UUID.class);

        verify(storageService).writeInput(jobIdCaptor.capture(), eq(Input.DESIGN), eq(design));

        UUID generatedJobId = jobIdCaptor.getValue();

        verify(storageService).writeInput(generatedJobId, Input.TESTBENCH, testbench);
        verify(storageService).deleteInput(generatedJobId, Input.DESIGN);
        verify(storageService).deleteInput(generatedJobId, Input.TESTBENCH);

        verifyNoInteractions(persistenceService, jobQueueService);
    }

    @Test
    void submitSimulation_shouldPreserveOriginalWriteFailureWhenCleanupFails() {
        String design = "module d; endmodule";
        RuntimeException writeFailure = new RuntimeException("write failed");

        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        doThrow(writeFailure).when(storageService).writeInput(any(UUID.class), eq(Input.DESIGN), eq(design));
        doThrow(new RuntimeException("cleanup failed")).when(storageService).deleteInput(any(UUID.class), eq(Input.DESIGN));

        assertThatThrownBy(() -> simulationService.submitSimulation(new SimulationRequest(design, null, null), userId)).isSameAs(writeFailure);

        verify(storageService).deleteInput(any(UUID.class), eq(Input.DESIGN));

        verifyNoInteractions(persistenceService, jobQueueService);
    }

    @Test
    void submitSimulation_shouldCleanupInputsAndNotPublishWhenJobWasDefinitelyNotCreated() {
        String design = "module d; endmodule";
        String testbench = "module tb; endmodule";

        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        when(persistenceService.persistNewJob(any())).thenReturn(DEFINITELY_NOT_CREATED);

        assertThatThrownBy(() -> simulationService.submitSimulation(new SimulationRequest(design, testbench, null), userId)).isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<UUID> jobIdCaptor = ArgumentCaptor.forClass(UUID.class);

        verify(storageService).writeInput(jobIdCaptor.capture(), eq(Input.DESIGN), eq(design));

        UUID generatedJobId = jobIdCaptor.getValue();

        verify(storageService).writeInput(generatedJobId, Input.TESTBENCH, testbench);
        verify(storageService).deleteInput(generatedJobId, Input.DESIGN);
        verify(storageService).deleteInput(generatedJobId, Input.TESTBENCH);

        verifyNoInteractions(jobQueueService);
    }

    @Test
    void submitSimulation_shouldRetainInputsAndNotPublishWhenCommitIsAmbiguous() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        when(persistenceService.persistNewJob(any())).thenReturn(COMMIT_AMBIGUOUS);

        assertThatThrownBy(() -> simulationService.submitSimulation(new SimulationRequest("module d; endmodule", null, null), userId)).isInstanceOf(IllegalStateException.class);

        verify(storageService, never()).deleteInput(any(), any());
        verifyNoInteractions(jobQueueService);
    }

    @Test
    void submitSimulation_shouldRetainInputsWhenQueuePublicationIsNotConfirmed() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        when(persistenceService.persistNewJob(any())).thenReturn(COMMITTED);
        when(jobQueueService.enqueueJob(any())).thenReturn(NOT_CONFIRMED);

        assertThatThrownBy(() -> simulationService.submitSimulation(new SimulationRequest("module d; endmodule", null, null), userId)).isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<SimulationJob> jobCaptor = ArgumentCaptor.forClass(SimulationJob.class);

        verify(persistenceService).persistNewJob(jobCaptor.capture());
        verify(jobQueueService).enqueueJob(jobCaptor.getValue().getJobId());

        verify(storageService, never()).deleteInput(any(), any());
    }
}

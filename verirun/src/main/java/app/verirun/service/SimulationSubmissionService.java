package app.verirun.service;

import app.verirun.dto.SimulationRequest;
import app.verirun.entity.SimulationJob;
import app.verirun.entity.User;
import app.verirun.repository.UserRepository;
import app.verirun.storage.SimulationStorageService;
import app.verirun.storage.SimulationStorageService.Input;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.UUID;

@Service
public class SimulationSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SimulationSubmissionService.class);

    private final UserRepository userRepository;
    private final VerilogSanitizerService sanitizer;
    private final SimulationStorageService storageService;
    private final SimulationJobPersistenceService persistenceService;
    private final JobQueueService jobQueueService;

    public SimulationSubmissionService(UserRepository userRepository, VerilogSanitizerService sanitizer, SimulationStorageService storageService, SimulationJobPersistenceService persistenceService, JobQueueService jobQueueService) {
        this.userRepository = userRepository;
        this.sanitizer = sanitizer;
        this.storageService = storageService;
        this.persistenceService = persistenceService;
        this.jobQueueService = jobQueueService;
    }

    public String submitSimulation(SimulationRequest request, UUID userId) {
        User owner = userRepository.findById(userId).orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + userId));

        sanitizer.sanitize(request.designCode());
        sanitizer.sanitize(request.testbenchCode());

        boolean testbenchExpected = request.testbenchCode() != null && !request.testbenchCode().isBlank();

        UUID jobId = UUID.randomUUID();
        EnumSet<Input> attemptedInputs = EnumSet.noneOf(Input.class);

        SimulationJob job = new SimulationJob(jobId.toString(), testbenchExpected, request.resolvedOptions(), owner);

        try {
            attemptedInputs.add(Input.DESIGN);
            storageService.writeInput(jobId, Input.DESIGN, request.designCode());

            if (testbenchExpected) {
                attemptedInputs.add(Input.TESTBENCH);
                storageService.writeInput(jobId, Input.TESTBENCH, request.testbenchCode());
            }
        } catch (RuntimeException e) {
            cleanupAttemptedInputs(jobId, attemptedInputs);
            throw e;
        }

        switch (persistenceService.persistNewJob(job)) {
            case COMMITTED -> {
            }
            case DEFINITELY_NOT_CREATED -> {
                cleanupAttemptedInputs(jobId, attemptedInputs);
                throw new IllegalStateException("Simulation job could not be created");
            }
            case COMMIT_AMBIGUOUS -> throw new IllegalStateException("Simulation job creation could not be confirmed");
        }

        if (jobQueueService.enqueueJob(jobId.toString()) != JobQueueService.PublicationResult.CONFIRMED) {
            throw new IllegalStateException("Simulation job publication could not be confirmed");
        }

        return jobId.toString();
    }

    private void cleanupAttemptedInputs(UUID jobId, EnumSet<Input> attemptedInputs) {
        for (Input input : attemptedInputs) {
            try {
                storageService.deleteInput(jobId, input);
            } catch (RuntimeException cleanupFailure) {
                log.warn("Failed to clean up unaccepted {} input for job {}", input.fileName(), jobId, cleanupFailure);
            }
        }
    }
}
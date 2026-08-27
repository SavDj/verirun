package app.verirun.service;

import app.verirun.entity.SimulationJob;
import app.verirun.repository.SimulationJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SimulationJobPersistenceService {

    private final SimulationJobRepository jobRepository;
    private final TransactionTemplate transactionTemplate;

    public SimulationJobPersistenceService(SimulationJobRepository jobRepository, PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public PersistenceOutcome persistNewJob(SimulationJob job) {
        TransactionCompletion completion = new TransactionCompletion();

        try {
            transactionTemplate.executeWithoutResult(status -> {
                completion.callbackStarted();
                TransactionSynchronizationManager.registerSynchronization(completion);
                jobRepository.saveAndFlush(job);
            });
        } catch (RuntimeException e) {
            if (!completion.callbackStartedSuccessfully() || completion.status() == TransactionSynchronization.STATUS_ROLLED_BACK) {
                return PersistenceOutcome.DEFINITELY_NOT_CREATED;
            }
            return PersistenceOutcome.COMMIT_AMBIGUOUS;
        }

        if (completion.status() == TransactionSynchronization.STATUS_COMMITTED) {
            return PersistenceOutcome.COMMITTED;
        }
        if (completion.status() == TransactionSynchronization.STATUS_ROLLED_BACK) {
            return PersistenceOutcome.DEFINITELY_NOT_CREATED;
        }
        return PersistenceOutcome.COMMIT_AMBIGUOUS;
    }

    public enum PersistenceOutcome {
        COMMITTED, DEFINITELY_NOT_CREATED, COMMIT_AMBIGUOUS
    }

    private static final class TransactionCompletion implements TransactionSynchronization {

        private boolean callbackStarted;
        private int status = STATUS_UNKNOWN;

        void callbackStarted() {
            callbackStarted = true;
        }

        boolean callbackStartedSuccessfully() {
            return callbackStarted;
        }

        int status() {
            return status;
        }

        @Override
        public void afterCompletion(int status) {
            this.status = status;
        }
    }
}

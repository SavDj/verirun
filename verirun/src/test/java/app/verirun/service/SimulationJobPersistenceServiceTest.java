package app.verirun.service;

import app.verirun.entity.SimulationJob;
import app.verirun.repository.SimulationJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static app.verirun.service.SimulationJobPersistenceService.PersistenceOutcome.COMMITTED;
import static app.verirun.service.SimulationJobPersistenceService.PersistenceOutcome.COMMIT_AMBIGUOUS;
import static app.verirun.service.SimulationJobPersistenceService.PersistenceOutcome.DEFINITELY_NOT_CREATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationJobPersistenceServiceTest {

    @Mock
    private SimulationJobRepository jobRepository;

    @Test
    void persistNewJob_shouldReturnCommittedWhenTransactionCommits() {
        ControlledTransactionManager transactionManager = new ControlledTransactionManager(TransactionSynchronization.STATUS_COMMITTED, false);

        SimulationJobPersistenceService persistenceService = new SimulationJobPersistenceService(jobRepository, transactionManager);

        SimulationJob job = new SimulationJob();

        assertThat(persistenceService.persistNewJob(job)).isEqualTo(COMMITTED);

        verify(jobRepository).saveAndFlush(job);
    }

    @Test
    void persistNewJob_shouldReturnDefinitelyNotCreatedWhenSaveRollsBack() {
        ControlledTransactionManager transactionManager = new ControlledTransactionManager(TransactionSynchronization.STATUS_COMMITTED, false);

        SimulationJobPersistenceService persistenceService = new SimulationJobPersistenceService(jobRepository, transactionManager);

        SimulationJob job = new SimulationJob();

        doThrow(new RuntimeException("database write failed")).when(jobRepository).saveAndFlush(job);

        assertThat(persistenceService.persistNewJob(job)).isEqualTo(DEFINITELY_NOT_CREATED);

        verify(jobRepository).saveAndFlush(job);
    }

    @Test
    void persistNewJob_shouldReturnDefinitelyNotCreatedWhenTransactionCannotStart() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenThrow(new TransactionSystemException("transaction unavailable"));

        SimulationJobPersistenceService persistenceService = new SimulationJobPersistenceService(jobRepository, transactionManager);

        assertThat(persistenceService.persistNewJob(new SimulationJob())).isEqualTo(DEFINITELY_NOT_CREATED);
        verifyNoInteractions(jobRepository);
    }

    @Test
    void persistNewJob_shouldReturnCommitAmbiguousWhenCommitOutcomeIsUnknown() {
        ControlledTransactionManager transactionManager = new ControlledTransactionManager(TransactionSynchronization.STATUS_UNKNOWN, true);

        SimulationJobPersistenceService persistenceService = new SimulationJobPersistenceService(jobRepository, transactionManager);

        SimulationJob job = new SimulationJob();

        assertThat(persistenceService.persistNewJob(job)).isEqualTo(COMMIT_AMBIGUOUS);

        verify(jobRepository).saveAndFlush(job);
    }

    private static final class ControlledTransactionManager implements PlatformTransactionManager {

        private final int commitCompletionStatus;
        private final boolean throwOnCommit;

        private ControlledTransactionManager(int commitCompletionStatus, boolean throwOnCommit) {
            this.commitCompletionStatus = commitCompletionStatus;
            this.throwOnCommit = throwOnCommit;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            TransactionSynchronizationManager.initSynchronization();

            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            complete(commitCompletionStatus);

            if (throwOnCommit) {
                throw new TransactionSystemException("commit result unknown");
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        private void complete(int status) {
            var synchronizations = TransactionSynchronizationManager.getSynchronizations();

            TransactionSynchronizationManager.clearSynchronization();

            synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
        }
    }
}

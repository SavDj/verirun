package app.verirun.service;

import app.verirun.dto.JobMessage;
import com.github.sonus21.rqueue.core.RqueueMessageEnqueuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static app.verirun.service.JobQueueService.PublicationResult.CONFIRMED;
import static app.verirun.service.JobQueueService.PublicationResult.NOT_CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobQueueServiceTest {

    @Mock
    private RqueueMessageEnqueuer messageEnqueuer;

    private JobQueueService queueService;

    @BeforeEach
    void setUp() {
        queueService = new JobQueueService(messageEnqueuer, "jobs");
    }

    @Test
    void enqueueJob_shouldConfirmGeneratedMessageIdAndRejectMissingId() {
        when(messageEnqueuer.enqueue(eq("jobs"), any(JobMessage.class)))
                .thenReturn("message-id")
                .thenReturn(null);

        assertThat(queueService.enqueueJob("one")).isEqualTo(CONFIRMED);
        assertThat(queueService.enqueueJob("two")).isEqualTo(NOT_CONFIRMED);

        ArgumentCaptor<JobMessage> messageCaptor = ArgumentCaptor.forClass(JobMessage.class);

        verify(messageEnqueuer, times(2)).enqueue(eq("jobs"), messageCaptor.capture());

        assertThat(messageCaptor.getAllValues()).extracting(JobMessage::getJobId).containsExactly("one", "two");
    }

    @Test
    void enqueueJob_shouldReturnNotConfirmedWhenRqueueThrows() {
        when(messageEnqueuer.enqueue(eq("jobs"), any(JobMessage.class))).thenThrow(new RuntimeException("queue error"));

        assertThat(queueService.enqueueJob("job-123")).isEqualTo(NOT_CONFIRMED);
    }
}

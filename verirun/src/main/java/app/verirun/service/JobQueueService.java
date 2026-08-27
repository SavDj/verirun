package app.verirun.service;

import app.verirun.dto.JobMessage;
import com.github.sonus21.rqueue.core.RqueueMessageEnqueuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JobQueueService {

    private static final Logger log = LoggerFactory.getLogger(JobQueueService.class);

    private final RqueueMessageEnqueuer rqueueMessageEnqueuer;
    private final String jobQueueName;

    public JobQueueService(
            RqueueMessageEnqueuer rqueueMessageEnqueuer,
            @Value("${app.queue.job-name:verirun:job-queue}") String jobQueueName) {
        this.rqueueMessageEnqueuer = rqueueMessageEnqueuer;
        this.jobQueueName = jobQueueName;
    }

    public PublicationResult enqueueJob(String jobId) {
        try {
            String messageId = rqueueMessageEnqueuer.enqueue(jobQueueName, new JobMessage(jobId));

            if (messageId == null || messageId.isBlank()) {
                log.warn("Queue publication was not confirmed for job {}", jobId);
                return PublicationResult.NOT_CONFIRMED;
            }

            log.debug("Job {} enqueued to {}", jobId, jobQueueName);
            return PublicationResult.CONFIRMED;
        } catch (RuntimeException e) {
            log.warn("Queue publication was not confirmed for job {}", jobId, e);
            return PublicationResult.NOT_CONFIRMED;
        }
    }

    public enum PublicationResult {
        CONFIRMED,
        NOT_CONFIRMED
    }
}

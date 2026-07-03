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

    public void enqueueJob(String jobId) {
        rqueueMessageEnqueuer.enqueue(jobQueueName, new JobMessage(jobId));
        log.debug("Job {} enqueued to {}", jobId, jobQueueName);
    }
}
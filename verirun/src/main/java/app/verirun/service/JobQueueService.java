package app.verirun.service;

import app.verirun.dto.JobMessage;
import com.github.sonus21.rqueue.core.RqueueMessageEnqueuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JobQueueService {

    private static final Logger log = LoggerFactory.getLogger(JobQueueService.class);
    public static final String JOB_QUEUE = "verirun:job-queue";

    private final RqueueMessageEnqueuer rqueueMessageEnqueuer;

    public JobQueueService(RqueueMessageEnqueuer rqueueMessageEnqueuer) {
        this.rqueueMessageEnqueuer = rqueueMessageEnqueuer;
    }

    public void enqueueJob(String jobId) {
        rqueueMessageEnqueuer.enqueue(JOB_QUEUE, new JobMessage(jobId));
        log.debug("Job {} enqueued", jobId);
    }
}

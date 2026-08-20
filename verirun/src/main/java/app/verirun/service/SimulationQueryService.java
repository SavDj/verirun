package app.verirun.service;

import app.verirun.dto.JobStatusResponse;
import app.verirun.entity.SimulationJob;
import app.verirun.repository.SimulationJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SimulationQueryService {

    private final SimulationJobRepository jobRepository;

    public SimulationQueryService(SimulationJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public Optional<JobStatusResponse> getJobStatus(String jobId, UUID userId) {
        return jobRepository.findByJobIdAndOwner_Id(jobId, userId).map(this::mapToResponse);
    }

    private JobStatusResponse mapToResponse(SimulationJob job) {
        String result = null;
        String buildMode = null;

        if (job.getStatus() == SimulationJob.JobStatus.COMPLETED && job.getResultJson() != null) {
            result = job.getResultJson();
            buildMode = job.getVerilatorOptions().buildMode().name();
        }

        return new JobStatusResponse(
                job.getJobId(),
                job.getStatus().name(),
                job.getCreatedAt().toString(),
                job.getStartedAt() != null ? job.getStartedAt().toString() : null,
                job.getCompletedAt() != null ? job.getCompletedAt().toString() : null,
                job.getErrorMessage(),
                job.getRetryCount(),
                result,
                buildMode
        );
    }
}

package app.verirun.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class JobMessage {

    private final String jobId;

    @JsonCreator
    public JobMessage(@JsonProperty("jobId") String jobId) {
        this.jobId = jobId;
    }

    public String getJobId() {
        return jobId;
    }
}

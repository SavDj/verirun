package app.verirun.dto;

public record JobStatusResponse(
        String jobId,
        String status,
        String createdAt,
        String startedAt,
        String completedAt,
        String errorMessage,
        Integer retryCount,
        String result,
        String buildMode
) {}
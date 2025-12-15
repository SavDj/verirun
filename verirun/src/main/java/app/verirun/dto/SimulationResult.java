package app.verirun.dto;

public record SimulationResult(
        String jobId,
        boolean passed,
        String logs,
        int exitCode
) {}
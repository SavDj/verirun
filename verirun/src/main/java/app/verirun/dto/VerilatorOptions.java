package app.verirun.dto;

import java.util.List;

public record VerilatorOptions(
        BuildMode buildMode,
        Boolean traceFst,
        Boolean traceVcd,
        Integer traceDepth,
        Boolean traceStruct,
        Boolean coverage,
        Integer parallelJobs,
        OptimizationLevel optLevel,
        List<String> includes,
        List<String> defines,
        List<String> warningsOff,
        List<String> extraFlags,
        List<String> simArgs
) {
    public enum BuildMode {
        LINT_ONLY,
        CC_MODEL,
        BINARY
    }

    public enum OptimizationLevel {
        O0, O1, O2, O3
    }

    public static VerilatorOptions defaults() {
        return new VerilatorOptions(
                BuildMode.BINARY,
                false,
                false,
                0,
                false,
                false,
                2,
                OptimizationLevel.O3,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    public VerilatorOptions {
        if (buildMode == null) buildMode = BuildMode.BINARY;
        if (traceFst == null) traceFst = false;
        if (traceVcd == null) traceVcd = false;
        if (traceDepth == null || traceDepth < 0 || traceDepth > 10) traceDepth = 0;
        if (traceStruct == null) traceStruct = false;
        if (coverage == null) coverage = false;
        if (parallelJobs == null || parallelJobs < 1 || parallelJobs > 16) parallelJobs = 2;
        if (optLevel == null) optLevel = OptimizationLevel.O3;
        if (includes == null) includes = List.of();
        if (defines == null) defines = List.of();
        if (warningsOff == null) warningsOff = List.of();
        if (extraFlags == null) extraFlags = List.of();
        if (simArgs == null) simArgs = List.of();
    }
}

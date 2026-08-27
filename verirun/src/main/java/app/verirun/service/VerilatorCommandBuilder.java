package app.verirun.service;

import app.verirun.dto.VerilatorOptions;
import app.verirun.dto.VerilatorOptions.BuildMode;
import app.verirun.dto.VerilatorOptions.OptimizationLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class VerilatorCommandBuilder {
    private static final Pattern DANGEROUS_EXTRA_FLAGS = Pattern.compile("(-CFLAGS|-LDFLAGS|-LDLIBS|--exe|--build|--make)");

    private final String uvmHome;

    public VerilatorCommandBuilder(@Value("${app.verilator.uvm-home:/usr/share/uvm}") String uvmHome) {
        this.uvmHome = uvmHome;
    }

    public String[] buildCommand(VerilatorOptions options, String topModule, boolean hasTestbench, boolean usesUvm) {
        List<String> cmd = new ArrayList<>();

        cmd.add("verilator");
        cmd.add("--timing");

        BuildMode mode = options.buildMode();
        if (mode == BuildMode.LINT_ONLY) {
            cmd.add("--lint-only");
        } else {
            cmd.add("-j");
            cmd.add(String.valueOf(options.parallelJobs()));
            cmd.add(optLevelFlag(options.optLevel()));

            if (mode == BuildMode.CC_MODEL) cmd.add("--cc");
            else if (mode == BuildMode.BINARY) cmd.add("--binary");
        }

        cmd.add("--top-module");
        cmd.add(topModule);

        if (options.traceVcd()) {
            cmd.add("--trace-vcd");
            if (options.traceDepth() > 0) {
                cmd.add("--trace-depth");
                cmd.add(String.valueOf(options.traceDepth()));
            }
        }

        if (options.traceFst()) {
            cmd.add("--trace-fst");
            if (options.traceDepth() > 0) {
                cmd.add("--trace-depth");
                cmd.add(String.valueOf(options.traceDepth()));
            }
        }

        if (options.traceStruct()) {
            cmd.add("--trace-structs");
        }

        if (options.coverage()) {
            cmd.add("--coverage");
        }

        addPrefixedFlags(cmd, options.includes(), "+incdir+");
        addPrefixedFlags(cmd, options.defines(), "+define+");
        addPrefixedFlags(cmd, options.warningsOff(), "-Wno-");

        if (usesUvm) {
            cmd.add("-Wno-fatal");
            cmd.add("+incdir+" + uvmHome);
            cmd.add("+define+UVM_NO_DPI");
            cmd.add("+incdir+.");
            cmd.add(uvmHome + "/uvm_pkg.sv");
        }

        for (String flag : options.extraFlags()) {
            if (!flag.isBlank()) {
                validateExtraFlag(flag);
                cmd.add(flag);
            }
        }

        cmd.add("design.sv");

        if (hasTestbench) {
            cmd.add("testbench.sv");
        }

        return cmd.toArray(new String[0]);
    }

    public String[] simulationCommand(String topModule, List<String> simArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add("./obj_dir/V" + topModule);
        if (simArgs != null) {
            for (String arg : simArgs) {
                if (!arg.isBlank()) {
                    cmd.add(arg);
                }
            }
        }
        return cmd.toArray(new String[0]);
    }

    private String optLevelFlag(OptimizationLevel level) {
        return switch (level) {
            case O0 -> "-O0";
            case O1 -> "-O1";
            case O2 -> "-O2";
            case O3 -> "-O3";
        };
    }

    private void addPrefixedFlags(List<String> cmd, List<String> flags, String prefix) {
        for (String flag : flags) {
            if (!flag.isBlank()) cmd.add(prefix + flag);
        }
    }

    private void validateExtraFlag(String flag) {
        if (DANGEROUS_EXTRA_FLAGS.matcher(flag).find()) {
            throw new SecurityException("Security Violation: Dangerous Verilator flag detected: " + flag);
        }
    }
}

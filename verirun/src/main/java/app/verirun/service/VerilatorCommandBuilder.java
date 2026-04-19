package app.verirun.service;

import app.verirun.dto.VerilatorOptions;
import app.verirun.dto.VerilatorOptions.BuildMode;
import app.verirun.dto.VerilatorOptions.OptimizationLevel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class VerilatorCommandBuilder {

    public String[] buildCommand(VerilatorOptions options, String topModule, boolean hasTestbench, boolean usesUvm) {
        List<String> cmd = new ArrayList<>();

        cmd.add("verilator");
        cmd.add("--timing");

        if (options.buildMode() == BuildMode.LINT_ONLY) {
            cmd.add("--lint-only");
        }

        if (options.buildMode() != BuildMode.LINT_ONLY) {
            cmd.add("-j");
            cmd.add(String.valueOf(options.parallelJobs()));
            cmd.add(optLevelFlag(options.optLevel()));
        }

        if (options.buildMode() == BuildMode.CC_MODEL) {
            cmd.add("--cc");
        } else if (options.buildMode() == BuildMode.BINARY) {
            cmd.add("--binary");
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

        for (String inc : options.includes()) {
            if (!inc.isBlank()) cmd.add("+incdir+" + inc);
        }

        for (String def : options.defines()) {
            if (!def.isBlank()) cmd.add("+define+" + def);
        }

        for (String wno : options.warningsOff()) {
            if (!wno.isBlank()) cmd.add("-Wno-" + wno);
        }

        if (usesUvm) {
            String uvm = resolveUvmHome();
            cmd.add("-Wno-fatal");
            cmd.add("+incdir+" + uvm);
            cmd.add("+define+UVM_NO_DPI");
            cmd.add("+incdir+.");
            cmd.add(uvm + "/uvm_pkg.sv");
        }

        for (String flag : options.extraFlags()) {
            if (!flag.isBlank()) cmd.add(flag);
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
            cmd.addAll(simArgs);
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

    private String resolveUvmHome() {
        String env = System.getenv("UVM_HOME");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return "/usr/share/uvm";
    }
}

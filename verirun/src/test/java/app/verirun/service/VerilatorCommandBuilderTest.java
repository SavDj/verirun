package app.verirun.service;

import app.verirun.dto.VerilatorOptions;
import app.verirun.dto.VerilatorOptions.BuildMode;
import app.verirun.dto.VerilatorOptions.OptimizationLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerilatorCommandBuilderTest {

    private VerilatorCommandBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new VerilatorCommandBuilder("/test/uvm/home");
    }

    @Nested
    class BuildCommandTests {

        @Test
        void buildCommand_shouldReturnBasicLintCommandWhenModeIsLintOnly() {
            VerilatorOptions options = new VerilatorOptions(BuildMode.LINT_ONLY, null, null, null, null, null, null, null,
                    null, null, null, null, null);

            String[] cmd = builder.buildCommand(options, "ALU", false, false);

            assertThat(cmd).containsExactly("verilator", "--timing", "--lint-only", "--top-module", "ALU", "design.sv");
        }

        @Test
        void buildCommand_shouldIncludeBinarySourcesAndTraceFlagsWhenConfigured() {
            VerilatorOptions options = new VerilatorOptions(BuildMode.BINARY, false, true, 5, false, false, 4, OptimizationLevel.O3,
                    List.of(), List.of(), List.of(), List.of(), List.of());

            String[] cmd = builder.buildCommand(options, "CPU", true, false);

            assertThat(cmd).startsWith("verilator", "--timing");
            assertThat(cmd).contains("--binary");
            assertThat(cmd).containsSubsequence("-j", "4");
            assertThat(cmd).contains("-O3");
            assertThat(cmd).containsSubsequence("--top-module", "CPU");
            assertThat(cmd).containsSubsequence("--trace-vcd", "--trace-depth", "5");
            assertThat(cmd).endsWith("design.sv", "testbench.sv");
            assertThat(cmd).doesNotContain("--cc", "--lint-only");
        }

        @Test
        void buildCommand_shouldIncludeCompleteUvmFlagsWhenUvmIsUsed() {
            VerilatorOptions options = new VerilatorOptions(BuildMode.CC_MODEL, true, false, 3, true, true, 2, OptimizationLevel.O0,
                    List.of(), List.of(), List.of(), List.of(), List.of());

            String[] cmd = builder.buildCommand(options, "UVM_TB", true, true);

            assertThat(cmd).contains("--cc", "--trace-fst", "--trace-structs", "--coverage", "-Wno-fatal",
                    "+incdir+/test/uvm/home", "+define+UVM_NO_DPI", "+incdir+.", "/test/uvm/home/uvm_pkg.sv");
            assertThat(cmd).containsSubsequence("--trace-depth", "3");
        }

        @Test
        void buildCommand_shouldFilterBlankCollectionFlagsAndKeepSafeExtraFlags() {
            VerilatorOptions options = new VerilatorOptions(BuildMode.LINT_ONLY, null, null, null, null, null, null, null,
                    List.of("valid_dir", "", "   "), List.of("DEBUG=1", "  ", "SIM_MODE"), List.of("UNUSED", " "),
                    List.of("--x-assign=fast", ""), List.of());

            String[] cmd = builder.buildCommand(options, "ALU", false, false);

            assertThat(cmd).contains("+incdir+valid_dir", "+define+DEBUG=1", "+define+SIM_MODE", "-Wno-UNUSED", "--x-assign=fast");
            assertThat(cmd).doesNotContain("+incdir+", "+define+", "-Wno-", "");
        }

        @ParameterizedTest
        @ValueSource(strings = {"-CFLAGS", "-LDFLAGS", "-LDLIBS", "--exe", "--build", "--make"})
        void buildCommand_shouldRejectDangerousExtraFlag(String flag) {
            VerilatorOptions options = new VerilatorOptions(BuildMode.LINT_ONLY, null, null, null, null, null, null, null,
                    null, null, null, List.of(flag), null);

            assertThatThrownBy(() -> builder.buildCommand(options, "ALU", false, false)).isInstanceOf(SecurityException.class);
        }
    }

    @Nested
    class SimulationCommandTests {

        @Test
        void simulationCommand_shouldIncludeOnlyNonBlankArgumentsWhenArgumentsAreProvided() {
            String[] cmd = builder.simulationCommand("ALU", List.of("+vcd+trace", "", "  ", "+verbose"));

            assertThat(cmd).containsExactly("./obj_dir/VALU", "+vcd+trace", "+verbose");
        }
    }
}

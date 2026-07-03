package app.verirun.service;

import app.verirun.dto.VerilatorOptions;
import app.verirun.dto.VerilatorOptions.BuildMode;
import app.verirun.dto.VerilatorOptions.OptimizationLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerilatorCommandBuilderTest {

    private VerilatorCommandBuilder builder;
    private VerilatorOptions options;

    @BeforeEach
    void setUp() {
        builder = new VerilatorCommandBuilder("/test/uvm/home");
        options = mock(VerilatorOptions.class);
        when(options.includes()).thenReturn(List.of());
        when(options.defines()).thenReturn(List.of());
        when(options.warningsOff()).thenReturn(List.of());
        when(options.extraFlags()).thenReturn(List.of());
    }

    @Nested
    class BuildCommandTests {

        @Test
        void buildCommand_shouldReturnBasicLintCommand_WhenLintOnlyMode() {
            when(options.buildMode()).thenReturn(BuildMode.LINT_ONLY);

            String[] cmd = builder.buildCommand(options, "ALU", false, false);

            assertThat(cmd).containsExactly(
                    "verilator", "--timing", "--lint-only", "--top-module", "ALU", "design.sv"
            );
        }

        @Test
        void buildCommand_shouldIncludeBinaryAndTraceFlags_WhenConfigured() {
            when(options.buildMode()).thenReturn(BuildMode.BINARY);
            when(options.parallelJobs()).thenReturn(4);
            when(options.optLevel()).thenReturn(OptimizationLevel.O3);
            when(options.traceVcd()).thenReturn(true);
            when(options.traceDepth()).thenReturn(5);

            String[] cmd = builder.buildCommand(options, "CPU", true, false);

            assertThat(cmd).contains("--binary", "-j", "4", "-O3", "--trace-vcd", "--trace-depth", "5", "testbench.sv");
        }

        @Test
        void buildCommand_shouldAddInjectedUvmFlags_WhenUsesUvmIsTrue() {
            when(options.buildMode()).thenReturn(BuildMode.CC_MODEL);
            when(options.parallelJobs()).thenReturn(2);
            when(options.optLevel()).thenReturn(OptimizationLevel.O0);

            String[] cmd = builder.buildCommand(options, "UVM_TB", true, true);

            assertThat(cmd).contains("-Wno-fatal", "+define+UVM_NO_DPI", "/test/uvm/home/uvm_pkg.sv");
        }

        @Test
        void buildCommand_shouldFilterOutBlankStrings_InIncludesAndDefines() {
            when(options.buildMode()).thenReturn(BuildMode.LINT_ONLY);
            when(options.includes()).thenReturn(List.of("valid_dir", "", "   "));
            when(options.defines()).thenReturn(List.of("DEBUG=1", "  ", "SIM_MODE"));
            when(options.warningsOff()).thenReturn(List.of("UNUSED"));

            String[] cmd = builder.buildCommand(options, "ALU", false, false);

            assertThat(cmd).contains("+incdir+valid_dir", "+define+DEBUG=1", "+define+SIM_MODE", "-Wno-UNUSED");
            assertThat(cmd).doesNotContain("+incdir+", "+define+", "-Wno-");
        }
    }

    @Nested
    class SimulationCommandTests {

        @Test
        void simulationCommand_shouldBuildCommandWithArgs_WhenArgsProvided() {
            String[] cmd = builder.simulationCommand("ALU", List.of("+vcd+trace", "+verbose"));

            assertThat(cmd).containsExactly("./obj_dir/VALU", "+vcd+trace", "+verbose");
        }

        @Test
        void simulationCommand_shouldBuildCommandWithoutArgs_WhenArgsAreNull() {
            String[] cmd = builder.simulationCommand("CPU", null);

            assertThat(cmd).containsExactly("./obj_dir/VCPU");
        }
    }
}
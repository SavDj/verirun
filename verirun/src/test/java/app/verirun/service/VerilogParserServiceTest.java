package app.verirun.service;

import app.verirun.exception.InvalidCodeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerilogParserServiceTest {

    private VerilogParserService parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new VerilogParserService(new VerilogSanitizerService());
    }

    @Test
    void resolveTopModule_shouldExtractModuleName_WhenValidModuleDeclared() throws IOException {
        Path tb = tempDir.resolve("tb.sv");
        Files.writeString(tb, "module ALU_tb(input a, b); \n assign y = a & b; \n endmodule");

        String moduleName = parser.resolveTopModule(tb);

        assertThat(moduleName).isEqualTo("ALU_tb");
    }

    @Test
    void resolveTopModule_shouldThrowIInvalidCodeException_WhenNoModuleDeclared() throws IOException {
        Path tb = tempDir.resolve("tb.sv");
        Files.writeString(tb, "// comment");

        assertThatThrownBy(() -> parser.resolveTopModule(tb))
                .isInstanceOf(InvalidCodeException.class)
                .hasMessageContaining("No 'module' declaration");
    }

    @Test
    void resolveTopModule_shouldReturnDefault_WhenFileDoesNotExist() throws IOException {
        Path tb = tempDir.resolve("non_existent.sv");

        String moduleName = parser.resolveTopModule(tb);

        assertThat(moduleName).isEqualTo("design_top");
    }

    @Test
    void detectUvmUsage_shouldReturnTrue_WhenUvmMacrosPresent() throws IOException {
        Path tb = tempDir.resolve("tb.sv");
        Files.writeString(tb, "`include \"uvm_macros.svh\"\nmodule tb; endmodule");

        boolean isUvm = parser.detectUvmUsage(tb);

        assertThat(isUvm).isTrue();
    }

    @Test
    void detectUvmUsage_shouldReturnFalse_WhenNoUvmReferences() throws IOException {
        Path tb = tempDir.resolve("tb.sv");
        Files.writeString(tb, "module tb; endmodule");

        boolean isUvm = parser.detectUvmUsage(tb);

        assertThat(isUvm).isFalse();
    }

    @Test
    void detectUvmUsage_shouldReturnFalse_WhenUvmIsOnlyInComments() throws IOException {
        Path tb = tempDir.resolve("tb.sv");
        Files.writeString(tb, "// import uvm_pkg::*;\nmodule tb; endmodule");

        boolean isUvm = parser.detectUvmUsage(tb);

        assertThat(isUvm).isFalse();
    }
}
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
    void resolveTopModule_shouldExtractModuleNameWhenValidModuleDeclared() throws IOException {
        Path tb = tempDir.resolve("tb.sv");
        Files.writeString(tb, "module ALU_tb(input a, b); \n assign y = a & b; \n endmodule");

        String moduleName = parser.resolveTopModule(tb);

        assertThat(moduleName).isEqualTo("ALU_tb");
    }

    @Test
    void resolveTopModule_shouldRejectWhenModuleExistsOnlyInCommentsOrStrings() throws IOException {
        Path tb = tempDir.resolve("tb.sv");
        Files.writeString(tb, """
                // module line_comment;
                /* module block_comment; */
                string description = "module string_literal;";
                """);

        assertThatThrownBy(() -> parser.resolveTopModule(tb)).isInstanceOf(InvalidCodeException.class);
    }

    @Test
    void detectUvmUsage_shouldReturnTrueWhenUvmPackageIsImported() throws IOException {
        Path tb = tempDir.resolve("tb.sv");
        Files.writeString(tb, "import uvm_pkg::*;\nmodule tb; endmodule");

        boolean isUvm = parser.detectUvmUsage(tb);

        assertThat(isUvm).isTrue();
    }

    @Test
    void detectUvmUsage_shouldReturnFalseWhenNoUvmReferences() throws IOException {
        Path tb = tempDir.resolve("tb.sv");
        Files.writeString(tb, "module tb; endmodule");

        boolean isUvm = parser.detectUvmUsage(tb);

        assertThat(isUvm).isFalse();
    }

    @Test
    void detectUvmUsage_shouldReturnFalseWhenUvmIsOnlyInCommentsOrStrings() throws IOException {
        Path tb = tempDir.resolve("tb.sv");
        Files.writeString(tb, """
                // import uvm_pkg::*;
                /* `uvm_info("id", "message", UVM_LOW) */
                module tb;
                  string description = "uvm_pkg";
                endmodule
                """);

        boolean isUvm = parser.detectUvmUsage(tb);

        assertThat(isUvm).isFalse();
    }
}

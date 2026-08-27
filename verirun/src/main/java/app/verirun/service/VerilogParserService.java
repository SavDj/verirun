package app.verirun.service;

import app.verirun.exception.InvalidCodeException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VerilogParserService {

    private static final Pattern MODULE_PATTERN = Pattern.compile("module\\s+([a-zA-Z_][a-zA-Z0-9_]*)");

    private final VerilogSanitizerService sanitizer;

    public VerilogParserService(VerilogSanitizerService sanitizer) {
        this.sanitizer = sanitizer;
    }

    public String resolveTopModule(Path testbenchPath) throws IOException {
        if (!Files.exists(testbenchPath)) return "design_top";

        String code = Files.readString(testbenchPath, StandardCharsets.UTF_8);
        String nakedCode = sanitizer.stripCommentsAndStrings(code);
        Matcher matcher = MODULE_PATTERN.matcher(nakedCode);

        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new InvalidCodeException("No 'module' declaration found in testbench");
    }

    public boolean detectUvmUsage(Path testbenchPath) throws IOException {
        if (!Files.exists(testbenchPath)) return false;

        String code = Files.readString(testbenchPath, StandardCharsets.UTF_8);

        String nakedCode = sanitizer.stripCommentsAndStrings(code).toLowerCase();

        return nakedCode.contains("uvm_pkg") || nakedCode.contains("`uvm");
    }
}
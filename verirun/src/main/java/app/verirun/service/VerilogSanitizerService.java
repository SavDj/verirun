package app.verirun.service;

import app.verirun.exception.InvalidCodeException;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class VerilogSanitizerService {

    private static final Pattern COMMENTS_AND_STRINGS_PATTERN = Pattern.compile(
            "//.*" +
                    "|/\\*[\\s\\S]*?\\*/" +
                    "|\"(?:\\\\.|[^\"\\\\])*\""
    );

    private static final Pattern INCLUDE_PATTERN = Pattern.compile("`include\\s*[\"<][^\">]+[\">]");

    private static final Pattern DANGEROUS_TASKS_PATTERN = Pattern.compile(
            "\\$(system|fopen|fwrite|fclose|fgetc|ungetc|fgets|fscanf|fread|fseek|ftell|rewind|fflush|ferror|feof|readmemh|readmemb)\\b"
    );

    public void sanitize(String code) {
        if (code == null || code.isBlank()) return;

        String nakedCode = stripCommentsAndStrings(code);

        if (INCLUDE_PATTERN.matcher(nakedCode).find()) {
            throw new InvalidCodeException("Security Violation: Verilog `include directives are disabled to prevent Local File Inclusion (LFI).");
        }

        if (DANGEROUS_TASKS_PATTERN.matcher(nakedCode).find()) {
            throw new InvalidCodeException("Security Violation: Verilog OS and Filesystem tasks ($system, $fopen, etc.) are disabled.");
        }
    }

    public String stripCommentsAndStrings(String code) {
        if (code == null || code.isBlank()) return "";
        return COMMENTS_AND_STRINGS_PATTERN.matcher(code).replaceAll(" ");
    }
}
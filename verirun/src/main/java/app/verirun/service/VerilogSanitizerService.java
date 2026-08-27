package app.verirun.service;

import app.verirun.exception.InvalidCodeException;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class VerilogSanitizerService {

    private static final Pattern COMMENTS_AND_STRINGS_PATTERN =
            Pattern.compile("//.*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"");

    private static final Pattern DANGEROUS_TASKS_PATTERN =
            Pattern.compile("\\$(system|fopen|fwrite|fclose|fgetc|ungetc|fgets|fscanf|fread|fseek|ftell|rewind|fflush|ferror|feof|readmemh|readmemb)\\b");

    public void sanitize(String code) {
        if (code == null || code.isBlank()) return;

        if (containsIncludeDirective(code)) {
            throw new InvalidCodeException("Security Violation: Verilog `include directives are disabled to prevent Local File Inclusion (LFI).");
        }

        String nakedCode = stripCommentsAndStrings(code);

        if (DANGEROUS_TASKS_PATTERN.matcher(nakedCode).find()) {
            throw new InvalidCodeException("Security Violation: Verilog OS and Filesystem tasks ($system, $fopen, etc.) are disabled.");
        }
    }

    public String stripCommentsAndStrings(String code) {
        if (code == null || code.isBlank()) return "";
        return COMMENTS_AND_STRINGS_PATTERN.matcher(code).replaceAll(" ");
    }

    private boolean containsIncludeDirective(String code) {
        boolean lineComment = false;
        boolean blockComment = false;
        boolean string = false;

        for (int i = 0; i < code.length(); i++) {
            char current = code.charAt(i);
            char next = i + 1 < code.length() ? code.charAt(i + 1) : '\0';

            if (lineComment) {
                if (current == '\n') lineComment = false;
                continue;
            }

            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }

            if (string) {
                if (current == '\\' && i + 1 < code.length()) {
                    i++;
                } else if (current == '"') {
                    string = false;
                }
                continue;
            }

            if (current == '/' && next == '/') {
                lineComment = true;
                i++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (current == '"') {
                string = true;
            } else if (code.startsWith("`include", i)) {
                int end = i + "`include".length();
                if (end == code.length() || !Character.isLetterOrDigit(code.charAt(end)) && code.charAt(end) != '_') {
                    return true;
                }
            }
        }

        return false;
    }
}
package app.verirun.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SimulationRequest(
        @NotBlank(message = "Design code is required")
        @Size(max = 100_000, message = "Design code too large (max 100KB)")
        String designCode,

        @Size(max = 100_000, message = "Testbench code too large (max 100KB)")
        String testbenchCode,

        @Valid
        VerilatorOptions options
) {
    public VerilatorOptions resolvedOptions() {
        return options != null ? options : VerilatorOptions.defaults();
    }
}
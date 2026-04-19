package app.verirun.dto;

public record SimulationRequest(
        String designCode,
        String testbenchCode,
        VerilatorOptions options
) {
    public VerilatorOptions resolvedOptions() {
        return options != null ? options : VerilatorOptions.defaults();
    }
}

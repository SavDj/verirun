package app.verirun.dto;

public record SimulationRequest(
        String designCode,
        String testbenchCode,
        boolean generateModelOnly
) {}
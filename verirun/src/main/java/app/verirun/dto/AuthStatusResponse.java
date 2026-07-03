package app.verirun.dto;

import java.util.UUID;

public record AuthStatusResponse(boolean authenticated, String email, UUID userId) {}

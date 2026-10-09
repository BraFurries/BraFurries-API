package com.Brafurries.API.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public final class LogConfigurationDtos {
    private LogConfigurationDtos() {}
    public record LogTarget(String id, String type, String name, String parentId, String parentName, Boolean writable, Boolean missing) {}
    public record LogConfiguration(String type, String label, boolean enabled, LogTarget target, List<String> warnings) {}
    public record LogsState(List<LogConfiguration> logs, List<LogTarget> targets, List<String> legacyTypes) {}
    public record UpdateLogRequest(@NotNull Boolean enabled, @Pattern(regexp = "[1-9]\\d*", message = "Destino inválido") String targetId) {}
    public record TestLogRequest(@NotNull @Pattern(regexp = "[1-9]\\d*", message = "Destino inválido") String targetId) {}
}

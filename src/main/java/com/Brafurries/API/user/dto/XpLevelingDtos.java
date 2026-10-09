package com.Brafurries.API.user.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class XpLevelingDtos {
    private XpLevelingDtos() {}

    public record XpConfigResponse(
        String guildId,
        XpTextConfig text,
        XpVoiceConfig voice,
        XpProgressionConfig progression,
        XpLimitsConfig limits,
        XpLevelUpConfig levelUp,
        XpAdvancedConfig advanced,
        XpReconciliationState reconciliation,
        List<String> levelUpPlaceholders,
        boolean comboRuntimeActive,
        boolean runtimeRefreshPending
    ) {}

    public record XpTextConfig(
        boolean enabled,
        int baseMin,
        int baseMax,
        int cooldownMinSeconds,
        int cooldownMaxSeconds
    ) {}

    public record XpVoiceConfig(
        boolean enabled,
        int xpPerMinute,
        BigDecimal socialBonusPct,
        int socialBonusMinHumans,
        int diminishingWindow1Minutes,
        int diminishingWindow2Minutes,
        BigDecimal diminishingFactor2,
        BigDecimal diminishingFactor3
    ) {}

    public record XpProgressionConfig(
        BigDecimal k,
        BigDecimal p,
        BigDecimal b
    ) {}

    public record XpLimitsConfig(
        int textDailyCapXp,
        int voiceDailyCapXp,
        int globalDailyCapXp
    ) {}

    public record XpLevelUpConfig(
        boolean enabled,
        String channelId,
        String message
    ) {}

    public record XpAdvancedConfig(
        boolean clearOnExit,
        BigDecimal multiplier,
        Integer dailyCombo,
        BigDecimal comboMultiplier
    ) {}

    public record XpReconciliationState(
        boolean required,
        LocalDateTime requestedAt,
        LocalDateTime reconciledAt
    ) {}

    public record XpConfigUpdateRequest(
        @NotNull @Valid XpTextUpdate text,
        @NotNull @Valid XpVoiceUpdate voice,
        @NotNull @Valid XpProgressionUpdate progression,
        @NotNull @Valid XpLimitsUpdate limits,
        @NotNull @Valid XpLevelUpUpdate levelUp,
        @NotNull @Valid XpAdvancedUpdate advanced
    ) {}

    public record XpTextUpdate(
        @NotNull Boolean enabled,
        @NotNull @Min(0) @Max(1000000) Integer baseMin,
        @NotNull @Min(0) @Max(1000000) Integer baseMax,
        @NotNull @Min(0) @Max(86400) Integer cooldownMinSeconds,
        @NotNull @Min(0) @Max(86400) Integer cooldownMaxSeconds
    ) {}

    public record XpVoiceUpdate(
        @NotNull Boolean enabled,
        @NotNull @Min(0) @Max(1000000) Integer xpPerMinute,
        @NotNull @DecimalMin("0") @Digits(integer = 4, fraction = 4) BigDecimal socialBonusPct,
        @NotNull @Min(2) @Max(1000) Integer socialBonusMinHumans,
        @NotNull @Min(1) @Max(525600) Integer diminishingWindow1Minutes,
        @NotNull @Min(1) @Max(525600) Integer diminishingWindow2Minutes,
        @NotNull @DecimalMin("0") @Digits(integer = 4, fraction = 4) BigDecimal diminishingFactor2,
        @NotNull @DecimalMin("0") @Digits(integer = 4, fraction = 4) BigDecimal diminishingFactor3
    ) {}

    public record XpProgressionUpdate(
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 3) BigDecimal k,
        @NotNull @DecimalMin("1") @Digits(integer = 17, fraction = 3) BigDecimal p,
        @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 3) BigDecimal b
    ) {}

    public record XpLimitsUpdate(
        @NotNull @Min(0) Integer textDailyCapXp,
        @NotNull @Min(0) Integer voiceDailyCapXp,
        @NotNull @Min(0) Integer globalDailyCapXp
    ) {}

    public record XpLevelUpUpdate(
        @NotNull Boolean enabled,
        @Pattern(regexp = "[1-9]\\d*", message = "channelId deve ser numérico e positivo")
        String channelId,
        @Size(max = 1800) String message
    ) {}

    public record XpAdvancedUpdate(
        @NotNull Boolean clearOnExit,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 3) BigDecimal multiplier
    ) {}

    public record XpSimulationRequest(
        @NotNull @Valid XpProgressionUpdate progression,
        @Size(max = 250) List<@Min(1) @Max(1000) Integer> levels
    ) {}

    public record XpSimulationResponse(
        String guildId,
        List<XpSimulationPoint> points,
        XpProgressionConfig progression
    ) {}

    public record XpSimulationPoint(
        int level,
        long totalXp,
        long xpFromPreviousLevel
    ) {}
}

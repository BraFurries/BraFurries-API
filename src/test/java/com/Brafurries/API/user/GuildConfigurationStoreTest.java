package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import com.Brafurries.API.user.dto.GuildManagementDtos.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class GuildConfigurationStoreTest {
    @Test void failsWhenSharedBaseConfigurationWasNotInitialized() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any())).thenReturn(0);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> new GuildConfigurationStore(jdbc).writeStaffRoleIds(123L, List.of("456")));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test void channelWritesDoNotCreateMissingSharedBaseConfiguration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GuildConfigurationStore store = new GuildConfigurationStore(jdbc);

        assertMissingBaseConflict(() -> store.writeBirthdayChannel(123L, "456"));
        assertMissingBaseConflict(() -> store.writeAiChannels(123L, true, List.of("456")));

        verify(jdbc, times(2)).update(startsWith("UPDATE config_server_settings"), any(Object[].class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void bumpProcessingUsesItsOwnFeatureKeyAndRetiresLegacyFallback() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("UPDATE config_server_settings"), any(Object[].class))).thenReturn(1);
        GuildConfigurationStore store = new GuildConfigurationStore(jdbc);

        store.writeBumpProcessingChannel(123L, "456");

        verify(jdbc).update("DELETE FROM allowed_feature_channels WHERE server_guild_id=? AND feature_key=?",
            123L, "disboard_bump_processing");
        verify(jdbc).update("UPDATE config_server_settings SET bump_warn_disboard_channel_id=NULL WHERE server_guild_id=?",
            123L);
        verify(jdbc).update("INSERT INTO allowed_feature_channels (server_guild_id, feature_key, channel_id) VALUES (?, ?, ?)",
            123L, "disboard_bump_processing", 456L);
        verifyNoMoreInteractions(jdbc);
    }

    @Test void clearingBumpProcessingAlsoRetiresLegacyFallbackWithoutInsertingCanonicalRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("UPDATE config_server_settings"), any(Object[].class))).thenReturn(1);
        GuildConfigurationStore store = new GuildConfigurationStore(jdbc);

        store.writeBumpProcessingChannel(123L, null);

        verify(jdbc).update("DELETE FROM allowed_feature_channels WHERE server_guild_id=? AND feature_key=?",
            123L, "disboard_bump_processing");
        verify(jdbc).update("UPDATE config_server_settings SET bump_warn_disboard_channel_id=NULL WHERE server_guild_id=?",
            123L);
        verifyNoMoreInteractions(jdbc);
    }

    @Test void bumpSectionWritesRejectMissingBaseAndMissingEconomy() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        GuildConfigurationStore store = new GuildConfigurationStore(jdbc);
        assertMissingBaseConflict(() -> store.writeBumpWarning(123L, false, null, null));
        assertMissingBaseConflict(() -> store.writeBumpRoleReward(123L,
            new BumpRoleRewardRequest(false, null, 0, null)));
        assertMissingBaseConflict(() -> store.writeBumpMonthlyRanking(123L,
            new BumpMonthlyRankingRequest(false, null, null, List.of()), tiers()));
        assertMissingBaseConflict(() -> store.writeBumpCoinReward(123L,
            new BumpCoinRewardRequest(false, 0)));
    }

    @Test void bumpWriteSqlKeepsSectionsAndOperationalStateIsReadOnly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        GuildConfigurationStore store = new GuildConfigurationStore(jdbc);
        store.writeBumpWarning(123L, true, "2", "[\"x\"]");
        store.writeBumpRoleReward(123L, new BumpRoleRewardRequest(true, "3", 10, "x"));
        store.writeBumpMonthlyRanking(123L, new BumpMonthlyRankingRequest(true, "4", "5", tiers()), tiers());
        store.writeBumpCoinReward(123L, new BumpCoinRewardRequest(true, 7));
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(4)).update(sql.capture(), any(Object[].class));
        String joined = String.join("\n", sql.getAllValues());
        assertTrue(joined.contains("bump_warn_enabled") && !joined.contains("bump_warn_disboard_channel_id")
            && !joined.contains("bump_warn_next_at") && !joined.contains("bump_warn_last_bump_at"));
        assertTrue(joined.contains("bump_reward_temp_role_id") && !joined.contains("SET bump_reward_coins"));
        assertTrue(joined.contains("bump_monthly_enabled"));
        assertTrue(joined.contains("UPDATE config_economy SET bump_reward_enabled=?, bump_points=?"));
    }

    @Test void legacyCoinFallbackOnlyAppliesWhenCanonicalCoinConfigurationIsUninitialized() {
        assertEquals(new BumpCoinRewardConfig(true, 25),
            GuildConfigurationStore.resolveCoinReward(null, 25));
        assertEquals(new BumpCoinRewardConfig(true, 25),
            GuildConfigurationStore.resolveCoinReward(new GuildConfigurationStore.EconomyBumpSettings(false, null), 25));
        assertEquals(new BumpCoinRewardConfig(false, 0),
            GuildConfigurationStore.resolveCoinReward(new GuildConfigurationStore.EconomyBumpSettings(false, 0), 25));
        assertEquals(new BumpCoinRewardConfig(true, 10),
            GuildConfigurationStore.resolveCoinReward(new GuildConfigurationStore.EconomyBumpSettings(true, 10), 25));
    }

    private static List<BumpMonthlyRewardTier> tiers() {
        return List.of(new BumpMonthlyRewardTier(1, 21, 1), new BumpMonthlyRewardTier(2, 14, 0),
            new BumpMonthlyRewardTier(3, 7, 0));
    }

    private static void assertMissingBaseConflict(Runnable write) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, write::run);
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }
}

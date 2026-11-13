/*
 * SPDX-License-Identifier: MIT
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015-2024 games647 and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.github.games647.fastlogin.core.shared;

import com.github.games647.craftapi.model.Profile;
import com.github.games647.craftapi.resolver.MojangResolver;
import com.github.games647.craftapi.resolver.RateLimitException;
import com.github.games647.fastlogin.core.shared.event.FastLoginPreLoginEvent;
import com.github.games647.fastlogin.core.storage.PremiumCheck;
import com.github.games647.fastlogin.core.storage.SQLStorage;
import com.github.games647.fastlogin.core.storage.StoredProfile;
import net.md_5.bungee.config.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.helpers.NOPLogger;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JoinManagementTest {

    private static final String NAME = "Notch";
    private static final String IP = "127.0.0.1";
    private static final UUID PREMIUM_ID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID OFFLINE_ID = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + NAME).getBytes(StandardCharsets.UTF_8)
    );

    private FastLoginCore<Object, Object, PlatformPlugin<Object>> core;
    private SQLStorage storage;
    private MojangResolver resolver;
    private Configuration config;
    private RecordingJoin join;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        core = mock(FastLoginCore.class);
        storage = mock(SQLStorage.class);
        resolver = mock(MojangResolver.class);
        PlatformPlugin<Object> plugin = mock(PlatformPlugin.class);
        config = new Configuration();

        when(plugin.getLog()).thenReturn(NOPLogger.NOP_LOGGER);
        when(core.getPlugin()).thenReturn(plugin);
        when(core.getConfig()).thenReturn(config);
        when(core.getStorage()).thenReturn(storage);
        when(core.getResolver()).thenReturn(resolver);
        when(resolver.findProfile(NAME)).thenReturn(Optional.of(new Profile(PREMIUM_ID, NAME)));

        config.set("detectPremiumByUuid", true);
        join = new RecordingJoin(core);
    }

    @Test
    void offlineUuidJoinsCracked() throws Exception {
        newPlayer();
        join.onLogin(NAME, new FakeSource(OFFLINE_ID));

        assertCracked();
        verify(resolver, never()).findProfile(anyString());
    }

    @Test
    void firstCheckRequestsPremiumAndSavesIt() throws Exception {
        newPlayer();
        long before = System.currentTimeMillis();
        join.onLogin(NAME, new FakeSource(PREMIUM_ID));

        assertEquals(Boolean.FALSE, join.premiumRequest, "premium login as unregistered player");
        assertFalse(join.cracked);
        assertSavedCheck(1, before, TimeUnit.DAYS.toMillis(1));
    }

    @Test
    void suspendedCheckJoinsCracked() throws Exception {
        newPlayer();
        long suspendedUntil = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1);
        when(storage.loadPremiumCheck(NAME)).thenReturn(new PremiumCheck(1, suspendedUntil));
        join.onLogin(NAME, new FakeSource(PREMIUM_ID));

        assertCracked();
        verify(resolver, never()).findProfile(anyString());
        verify(storage, never()).savePremiumCheck(anyString(), anyInt(), anyLong());
    }

    @Test
    void expiredCheckRetriesWithNextDelay() throws Exception {
        newPlayer();
        when(storage.loadPremiumCheck(NAME)).thenReturn(new PremiumCheck(1, System.currentTimeMillis() - 1));
        long before = System.currentTimeMillis();
        join.onLogin(NAME, new FakeSource(PREMIUM_ID));

        assertEquals(Boolean.FALSE, join.premiumRequest);
        assertSavedCheck(2, before, TimeUnit.DAYS.toMillis(7));
    }

    @Test
    void lastDelayRepeats() throws Exception {
        newPlayer();
        when(storage.loadPremiumCheck(NAME)).thenReturn(new PremiumCheck(5, 0));
        long before = System.currentTimeMillis();
        join.onLogin(NAME, new FakeSource(PREMIUM_ID));

        assertSavedCheck(6, before, TimeUnit.DAYS.toMillis(30));
    }

    @Test
    void saveErrorJoinsCracked() throws Exception {
        newPlayer();
        doThrow(new SQLException("test")).when(storage).savePremiumCheck(anyString(), anyInt(), anyLong());
        join.onLogin(NAME, new FakeSource(PREMIUM_ID));

        assertCracked();
    }

    @Test
    void loadErrorJoinsCracked() throws Exception {
        newPlayer();
        when(storage.loadPremiumCheck(NAME)).thenThrow(new SQLException("test"));
        join.onLogin(NAME, new FakeSource(PREMIUM_ID));

        assertCracked();
        verify(resolver, never()).findProfile(anyString());
    }

    @Test
    void pendingLoginAttemptDoesNotMatter() {
        // the saved check replaces secondAttemptCracked on this path
        newPlayer();
        when(core.hasFailedLogin(IP, NAME)).thenReturn(true);
        join.onLogin(NAME, new FakeSource(PREMIUM_ID));

        assertEquals(Boolean.FALSE, join.premiumRequest);
    }

    @Test
    void otherPremiumUuidJoinsCracked() {
        newPlayer();
        join.onLogin(NAME, new FakeSource(UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6")));

        assertCracked();
    }

    @Test
    void missingUuidJoinsCracked() throws Exception {
        newPlayer();
        join.onLogin(NAME, new FakeSource(null));

        assertCracked();
        verify(resolver, never()).findProfile(anyString());
    }

    @Test
    void mojangErrorJoinsCracked() throws Exception {
        newPlayer();
        when(resolver.findProfile(NAME)).thenThrow(new RateLimitException());
        join.onLogin(NAME, new FakeSource(PREMIUM_ID));

        assertCracked();
        verify(storage, never()).savePremiumCheck(anyString(), anyInt(), anyLong());
    }

    @Test
    void disabledKeepsOriginalBehavior() throws Exception {
        config.set("detectPremiumByUuid", false);
        newPlayer();
        join.onLogin(NAME, new FakeSource(PREMIUM_ID));

        assertCracked();
        verify(resolver, never()).findProfile(anyString());
        verify(storage, never()).loadPremiumCheck(anyString());
        verify(core, times(1)).hasFailedLogin(IP, NAME);
    }

    @Test
    void existingCrackedRowWithMatchingUuidRequestsPremium() {
        existingPlayer(false);
        join.onLogin(NAME, new FakeSource(PREMIUM_ID));

        assertEquals(Boolean.FALSE, join.premiumRequest);
        assertFalse(join.cracked);
    }

    @Test
    void existingCrackedRowWithOfflineUuidJoinsCracked() {
        existingPlayer(false);
        join.onLogin(NAME, new FakeSource(OFFLINE_ID));

        assertCracked();
    }

    @Test
    void registeredPremiumUnchanged() throws Exception {
        existingPlayer(true);
        join.onLogin(NAME, new FakeSource(OFFLINE_ID));

        assertEquals(Boolean.TRUE, join.premiumRequest, "premium login as registered player");
        assertFalse(join.cracked);
        verify(resolver, never()).findProfile(anyString());
        verify(storage, never()).loadPremiumCheck(anyString());
        verify(core, never()).hasFailedLogin(anyString(), anyString());
    }

    private void assertSavedCheck(int failures, long before, long delay) throws SQLException {
        ArgumentCaptor<Long> suspendedUntil = ArgumentCaptor.forClass(Long.class);
        verify(storage).savePremiumCheck(eq(NAME), eq(failures), suspendedUntil.capture());

        long after = System.currentTimeMillis();
        assertTrue(suspendedUntil.getValue() >= before + delay && suspendedUntil.getValue() <= after + delay,
                "suspended for the expected delay");
    }

    private void newPlayer() {
        when(storage.loadProfile(NAME)).thenReturn(new StoredProfile(null, NAME, false, FloodgateState.FALSE, ""));
    }

    private void existingPlayer(boolean premium) {
        StoredProfile profile = new StoredProfile(1, premium ? PREMIUM_ID : null, NAME, premium,
                FloodgateState.FALSE, IP, Instant.now());
        when(storage.loadProfile(NAME)).thenReturn(profile);
    }

    private void assertCracked() {
        assertTrue(join.cracked, "cracked session");
        assertNull(join.premiumRequest, "no premium request");
    }

    private static final class FakeSource implements LoginSource {

        private final UUID clientId;

        FakeSource(UUID clientId) {
            this.clientId = clientId;
        }

        @Override
        public void enableOnlinemode() {
        }

        @Override
        public void kick(String message) {
        }

        @Override
        public InetSocketAddress getAddress() {
            return new InetSocketAddress(IP, 25565);
        }

        @Override
        public UUID getClientUniqueId() {
            return clientId;
        }
    }

    private static final class RecordingJoin extends JoinManagement<Object, Object, LoginSource> {

        private Boolean premiumRequest;
        private boolean cracked;

        RecordingJoin(FastLoginCore<Object, Object, ?> core) {
            super(core, null, null);
        }

        @Override
        public FastLoginPreLoginEvent callFastLoginPreLoginEvent(String username, LoginSource source,
                                                                 StoredProfile profile) {
            return null;
        }

        @Override
        public void requestPremiumLogin(LoginSource source, StoredProfile profile, String username,
                                        boolean registered) {
            premiumRequest = registered;
        }

        @Override
        public void startCrackedSession(LoginSource source, StoredProfile profile, String username) {
            cracked = true;
        }
    }
}

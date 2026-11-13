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
package com.github.games647.fastlogin.velocity.listener;

import com.github.games647.fastlogin.core.antibot.AntiBotService;
import com.github.games647.fastlogin.core.scheduler.AsyncScheduler;
import com.github.games647.fastlogin.core.shared.FastLoginCore;
import com.github.games647.fastlogin.core.shared.FloodgateState;
import com.github.games647.fastlogin.core.storage.SQLStorage;
import com.github.games647.fastlogin.core.storage.StoredProfile;
import com.github.games647.fastlogin.velocity.FastLoginVelocity;
import com.github.games647.fastlogin.velocity.VelocityLoginSession;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.util.GameProfile;
import net.md_5.bungee.config.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectListenerTest {

    private static final String NAME = "Notch";
    private static final UUID PREMIUM_ID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private final InetSocketAddress address = new InetSocketAddress("127.0.0.1", 25565);
    private final Configuration config = new Configuration();
    private SQLStorage storage;
    private InboundConnection connection;
    private ConnectListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        FastLoginVelocity plugin = mock(FastLoginVelocity.class);
        FastLoginCore<Player, CommandSource, FastLoginVelocity> core = mock(FastLoginCore.class);
        AsyncScheduler scheduler = mock(AsyncScheduler.class);
        storage = mock(SQLStorage.class);
        connection = mock(InboundConnection.class);

        // run scheduled tasks right away
        when(scheduler.runAsync(any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return CompletableFuture.completedFuture(null);
        });

        StoredProfile profile = new StoredProfile(null, NAME, false, FloodgateState.FALSE, "");
        ConcurrentHashMap<InetSocketAddress, VelocityLoginSession> sessions = new ConcurrentHashMap<>();
        sessions.put(address, new VelocityLoginSession(NAME, false, profile));

        when(plugin.getLog()).thenReturn(NOPLogger.NOP_LOGGER);
        when(plugin.getCore()).thenReturn(core);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getSession()).thenReturn(sessions);
        when(core.getConfig()).thenReturn(config);
        when(core.getStorage()).thenReturn(storage);
        when(connection.getRemoteAddress()).thenReturn(address);

        config.set("detectPremiumByUuid", true);
        listener = new ConnectListener(plugin, mock(AntiBotService.class));
    }

    @Test
    void passedCheckIsDeleted() throws Exception {
        listener.onGameProfileRequest(profileRequest(true));

        verify(storage).deletePremiumCheck(NAME);
    }

    @Test
    void offlineLoginKeepsCheck() throws Exception {
        listener.onGameProfileRequest(profileRequest(false));

        verify(storage, never()).deletePremiumCheck(anyString());
    }

    private GameProfileRequestEvent profileRequest(boolean onlineMode) {
        GameProfile profile = new GameProfile(PREMIUM_ID, NAME, Collections.emptyList());
        return new GameProfileRequestEvent(connection, profile, onlineMode);
    }
}

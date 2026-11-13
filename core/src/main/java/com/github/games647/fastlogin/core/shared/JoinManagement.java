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
import com.github.games647.craftapi.resolver.RateLimitException;
import com.github.games647.fastlogin.core.hooks.AuthPlugin;
import com.github.games647.fastlogin.core.hooks.bedrock.BedrockService;
import com.github.games647.fastlogin.core.shared.event.FastLoginPreLoginEvent;
import com.github.games647.fastlogin.core.storage.PremiumCheck;
import com.github.games647.fastlogin.core.storage.StoredProfile;
import net.md_5.bungee.config.Configuration;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public abstract class JoinManagement<P extends C, C, S extends LoginSource> {

    private static final List<Integer> DEFAULT_RETRY_DAYS = Arrays.asList(1, 7, 30);

    protected final FastLoginCore<P, C, ?> core;
    protected final AuthPlugin<P> authHook;
    private final BedrockService<?> bedrockService;

    public JoinManagement(FastLoginCore<P, C, ?> core, AuthPlugin<P> authHook, BedrockService<?> bedrockService) {
        this.core = core;
        this.authHook = authHook;
        this.bedrockService = bedrockService;
    }

    public void onLogin(String username, S source) {
        //check if the player is connecting through Bedrock Edition
        if (bedrockService != null && bedrockService.isBedrockConnection(username)) {
            //perform Bedrock specific checks and skip Java checks if no longer needed
            if (bedrockService.performChecks(username, source)) {
                return;
            }
        }

        StoredProfile profile = core.getStorage().loadProfile(username);
        //can't be a premium Java player, if it's not saved in the database
        if (profile == null) {
            return;
        }

        if (profile.isFloodgateMigrated()) {
            if (profile.getFloodgate() == FloodgateState.TRUE) {
                // migrated and enabled floodgate player, however the above bedrocks fails, so the current connection
                // isn't premium
                return;
            }
        } else {
            profile.setFloodgate(FloodgateState.FALSE);
            core.getPlugin().getLog().info(
                    "Player {} will be migrated to the v2 database schema as a JAVA user", username
            );
        }

        callFastLoginPreLoginEvent(username, source, profile);

        String ip = source.getAddress().getAddress().getHostAddress();
        profile.setLastIp(ip);
        if (profile.isExistingPlayer() && profile.isOnlinemodePreferred()) {
            core.getPlugin().getLog().info("Requesting premium login for registered player: {}", username);
            requestPremiumLogin(source, profile, username, true);
            return;
        }

        if (core.getConfig().get("detectPremiumByUuid", false) && requestPremiumCheck(source, profile, username)) {
            return;
        }

        if (profile.isExistingPlayer()) {
            if (isValidUsername(source, profile)) {
                startCrackedSession(source, profile, username);
            }
        } else {
            performNewPlayerLogin(username, source, ip, profile);
        }
    }

    /**
     * Requests a premium login if the client sent the UUID of the premium account of this name. The check is saved as
     * failed before, and only deleted once Mojang verified the login. While a failed check is suspended, the name joins
     * as cracked without being asked again, so a failure is seen once and not on every join.
     *
     * @param source connection of the player
     * @param profile stored profile of this name
     * @param username name sent by the client
     * @return true if a premium login was requested
     */
    private boolean requestPremiumCheck(S source, StoredProfile profile, String username) {
        // premium clients send the id of their account (version 4), offline clients usually a name based one (3)
        UUID clientId = source.getClientUniqueId();
        if (clientId == null || clientId.version() != 4) {
            return false;
        }

        long now = System.currentTimeMillis();
        PremiumCheck lastCheck;
        try {
            lastCheck = core.getStorage().loadPremiumCheck(username);
        } catch (SQLException sqlEx) {
            core.getPlugin().getLog().warn("Failed to load the premium check of {}: {}", username, sqlEx.toString());
            return false;
        }

        if ((lastCheck != null && lastCheck.getSuspendedUntil() > now) || !isPremiumUuid(clientId, username)) {
            return false;
        }

        int failures = lastCheck == null ? 1 : lastCheck.getFailures() + 1;
        try {
            core.getStorage().savePremiumCheck(username, failures, now + getRetryDelay(failures));
        } catch (SQLException sqlEx) {
            // without a saved check, a failure would be asked again on every join
            core.getPlugin().getLog().warn("Failed to save the premium check of {}: {}", username, sqlEx.toString());
            return false;
        }

        core.getPlugin().getLog().info("Client of {} sent the UUID of its premium account", username);
        requestPremiumLogin(source, profile, username, false);
        return true;
    }

    private boolean isPremiumUuid(UUID clientId, String username) {
        try {
            Optional<Profile> premiumProfile = core.getResolver().findProfile(username);
            return premiumProfile.isPresent() && clientId.equals(premiumProfile.get().getId());
        } catch (RateLimitException rateLimitEx) {
            core.getPlugin().getLog().warn("Mojang's rate limit reached, cannot check the client UUID of {}", username);
        } catch (Exception ex) {
            core.getPlugin().getLog().warn("Failed to check the client UUID of {}: {}", username, ex.toString());
        }

        return false;
    }

    private long getRetryDelay(int failures) {
        List<Integer> retryDays = core.getConfig().getIntList("detectPremiumRetryDays");
        if (retryDays.isEmpty()) {
            retryDays = DEFAULT_RETRY_DAYS;
        }

        // the last delay applies to every further failure
        int days = retryDays.get(Math.min(failures, retryDays.size()) - 1);
        return TimeUnit.DAYS.toMillis(Math.max(days, 0));
    }

    private void performNewPlayerLogin(String username, S source, String ip, StoredProfile profile) {
        try {
            if (core.hasFailedLogin(ip, username)) {
                core.getPlugin().getLog().info("Second attempt login -> cracked {}", username);

                //first login request failed so make a cracked session
                startCrackedSession(source, profile, username);
                return;
            }

            Configuration config = core.getConfig();
            Optional<Profile> premiumUUID = Optional.empty();
            if (config.get("nameChangeCheck", false) || config.get("autoRegister", false)) {
                premiumUUID = core.getResolver().findProfile(username);
            }

            if (!premiumUUID.isPresent()
                    || (!isNameChanged(source, username, premiumUUID.get())
                    && !isUsernameAvailable(source, username, profile))) {
                //nothing detected the player as premium -> start a cracked session
                if (core.getConfig().get("switchMode", false)) {
                    source.kick(core.getMessage("switch-kick-message"));
                    return;
                }

                startCrackedSession(source, profile, username);
            }
        } catch (RateLimitException rateLimitEx) {
            core.getPlugin().getLog().error("Mojang's rate limit reached for {}. The public IPv4 address of this"
                    + " server issued more than 600 Name -> UUID requests within 10 minutes. After those 10"
                    + " minutes we can make requests again.", username);
        } catch (Exception ex) {
            core.getPlugin().getLog().error("Failed to check premium state of {}", username, ex);
        }
    }

    protected boolean isValidUsername(LoginSource source, StoredProfile profile) {
        if (bedrockService != null && bedrockService.isUsernameForbidden(profile)) {
            core.getPlugin().getLog().info("Floodgate Prefix detected on cracked player");
            source.kick("Your username contains illegal characters");
            return false;
        }

        return true;
    }

    private boolean isUsernameAvailable(S source, String username, StoredProfile profile) throws Exception {
        core.getPlugin().getLog().info("GameProfile {} uses a premium username", username);
        if (core.getConfig().get("autoRegister", false) && (authHook == null || !authHook.isRegistered(username))) {
            requestPremiumLogin(source, profile, username, false);
            return true;
        }

        return false;
    }

    private boolean isNameChanged(S source, String username, Profile profile) {
        //user not exists in the db
        if (core.getConfig().get("nameChangeCheck", false)) {
            StoredProfile storedProfile = core.getStorage().loadProfile(profile.getId());
            if (storedProfile != null) {
                if (storedProfile.getFloodgate() == FloodgateState.TRUE) {
                    core.getPlugin().getLog()
                            .info("Player {} is already stored by FastLogin as a Bedrock Edition player.", username);
                    return false;
                }

                //uuid exists in the database
                core.getPlugin().getLog().info("GameProfile {} changed it's username", profile);

                //update the username to the new one in the database
                storedProfile.setPlayerName(username);

                requestPremiumLogin(source, storedProfile, username, false);
                return true;
            }
        }

        return false;
    }

    public abstract FastLoginPreLoginEvent callFastLoginPreLoginEvent(String username, S source, StoredProfile profile);

    public abstract void requestPremiumLogin(S source, StoredProfile profile, String username, boolean registered);

    public abstract void startCrackedSession(S source, StoredProfile profile, String username);
}

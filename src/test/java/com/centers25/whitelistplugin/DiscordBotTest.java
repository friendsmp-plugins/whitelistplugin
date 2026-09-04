package com.centers25.whitelistplugin;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordBotTest {
    @Test
    void permitsConfiguredChannelsAndTheirThreads() {
        Set<String> channels = Set.of("forum", "text");

        assertTrue(DiscordBot.listed(channels, "text", ""));
        assertTrue(DiscordBot.listed(channels, "post", "forum"));
        assertFalse(DiscordBot.listed(channels, "other", "elsewhere"));
    }
}

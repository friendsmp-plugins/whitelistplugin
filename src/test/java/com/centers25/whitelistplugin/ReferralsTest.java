package com.centers25.whitelistplugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferralsTest {
    @TempDir
    Path directory;

    @Test
    void limitsReferrerAndPersistsOfflineTickets() throws IOException {
        Path file = directory.resolve("referrals.json");
        Referrals referrals = new Referrals(null, file);

        assertTrue(referrals.reward("123456789012345678", "Referrer", "PlayerOne"));
        assertTrue(referrals.reward("123456789012345678", "Referrer", "PlayerTwo"));
        assertTrue(referrals.reward("123456789012345678", "Referrer", ".PlayerThree"));
        assertFalse(referrals.reward("123456789012345678", "Referrer", "PlayerFour"));

        Referrals loaded = new Referrals(null, file);
        assertEquals(3, loaded.count("123456789012345678"));
        assertEquals(48, loaded.pending("Referrer"));
        assertEquals(8, loaded.pending("PlayerOne"));
        assertEquals(8, loaded.pending(".PlayerThree"));
    }

    @Test
    void acceptsAllSupportedUsernameTypes() {
        assertTrue(Referrals.valid("Premium_Player"));
        assertTrue(Referrals.valid("-Cracked_Player"));
        assertTrue(Referrals.valid(".Bedrock_Player"));
        assertFalse(Referrals.valid("Player; stop"));
    }
}

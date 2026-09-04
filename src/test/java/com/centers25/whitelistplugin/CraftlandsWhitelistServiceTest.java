package com.centers25.whitelistplugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftlandsWhitelistServiceTest {
    @Test
    void acceptsSupportedNamesAndRejectsCommandInjection() {
        assertTrue(CraftlandsWhitelistService.valid("Notch"));
        assertTrue(CraftlandsWhitelistService.valid(".Uzolius"));
        assertTrue(CraftlandsWhitelistService.valid("-Player_123"));
        assertFalse(CraftlandsWhitelistService.valid("Player; stop"));
        assertFalse(CraftlandsWhitelistService.valid("Player\nstop"));
    }
}

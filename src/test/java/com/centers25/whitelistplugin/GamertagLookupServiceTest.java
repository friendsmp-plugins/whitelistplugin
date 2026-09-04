package com.centers25.whitelistplugin;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GamertagLookupServiceTest {
    @Test
    void formatsMojangUuid() {
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5",
                GamertagLookupService.formatDashedUuid("069a79f444e94726a5befca90e38aaf5"));
    }

    @Test
    void autoEditionUsesDotForBedrock() {
        assertEquals(GamertagLookupService.Edition.BEDROCK,
                GamertagLookupService.resolveEdition(".Bedrock Name", "auto"));
        assertEquals(GamertagLookupService.Edition.JAVA,
                GamertagLookupService.resolveEdition("Notch", "auto"));
    }

    @Test
    void jwkCoordinatesAreAlwaysThirtyTwoBytes() {
        String encoded = MicrosoftXboxAuthService.base64UrlUnsigned(BigInteger.ONE, 32);
        assertEquals(32, Base64.getUrlDecoder().decode(encoded).length);
    }

    @Test
    void lookupResponseIncludesCanonicalNameOnlyWhenValid() {
        assertEquals("True - Notch", new GamertagLookupService.LookupResult(
                GamertagLookupService.Edition.JAVA,
                GamertagLookupService.Status.EXISTS,
                "notch", Map.of("Username", "Notch"), null).toDiscordMessage());
        assertEquals("True - .Uzolius", new GamertagLookupService.LookupResult(
                GamertagLookupService.Edition.BEDROCK,
                GamertagLookupService.Status.EXISTS,
                "uzolius", Map.of("Gamertag", "Uzolius"), null).toDiscordMessage());
        assertEquals(".Bedrock_Name", new GamertagLookupService.LookupResult(
                GamertagLookupService.Edition.BEDROCK,
                GamertagLookupService.Status.EXISTS,
                "Bedrock Name", Map.of("Gamertag", "Bedrock Name"), null).name());
        assertEquals("False", new GamertagLookupService.LookupResult(
                GamertagLookupService.Edition.BEDROCK,
                GamertagLookupService.Status.NOT_FOUND,
                "missing", Map.of(), null).toDiscordMessage());
        assertEquals("False", new GamertagLookupService.LookupResult(
                GamertagLookupService.Edition.JAVA,
                GamertagLookupService.Status.INVALID,
                "invalid", Map.of(), null).toDiscordMessage());
    }

    @Test
    void crackedJavaNamesAreValidatedLocallyAndPrefixed() {
        assertTrue(GamertagLookupService.validCracked(" Player_123 "));
        assertEquals("-Player_123", GamertagLookupService.crackedName(" Player_123 "));
        assertFalse(GamertagLookupService.validCracked("bad-name"));
        assertFalse(GamertagLookupService.validCracked("ab"));
        assertFalse(GamertagLookupService.validCracked("this_name_is_way_too_long"));
    }
}

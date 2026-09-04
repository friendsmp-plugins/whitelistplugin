package com.centers25.whitelistplugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExistingPrismarineCacheIntegrationTest {
    @Test
    @EnabledIfSystemProperty(named = "prismarine.cache", matches = ".+")
    void exchangesExistingMicrosoftAccessTokenAndLooksUpBedrock() throws Exception {
        JsonObject source = JsonParser.parseString(Files.readString(Path.of(System.getProperty("prismarine.cache"))))
                .getAsJsonObject().getAsJsonObject("token");
        Path temporary = Files.createTempDirectory("whitelistplugin-auth-test").resolve("tokens.json");
        TokenStore store = new TokenStore(temporary);
        store.save(new TokenStore.TokenState(
                source.get("access_token").getAsString(),
                source.get("refresh_token").getAsString(),
                System.currentTimeMillis() / 1000 + 3600,
                null, null, null, 0));

        MicrosoftXboxAuthService auth = new MicrosoftXboxAuthService(
                "00000000441cc96b", temporary, Duration.ofSeconds(30), Duration.ofMinutes(15));
        MicrosoftXboxAuthService.DeviceCode deviceCode = auth.beginDeviceLogin();
        assertNotNull(deviceCode.userCode());
        assertFalse(deviceCode.userCode().isBlank());

        MicrosoftXboxAuthService.XboxCredential credential = auth.getOrRefreshCredential();
        assertNotNull(credential.token());
        assertFalse(credential.token().isBlank());

        GamertagLookupService lookups = new GamertagLookupService(auth, Duration.ofSeconds(30));
        GamertagLookupService.LookupResult bedrock = lookups.lookup(".Uzolius", "auto");
        GamertagLookupService.LookupResult java = lookups.lookup("Notch", "java");
        assertEquals(GamertagLookupService.Status.EXISTS, bedrock.status());
        assertEquals(GamertagLookupService.Status.EXISTS, java.status());
    }
}

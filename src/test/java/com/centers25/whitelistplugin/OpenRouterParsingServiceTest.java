package com.centers25.whitelistplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenRouterParsingServiceTest {
    @Test
    void decodesBedrockApplication() throws IOException {
        OpenRouterParsingService.App app = OpenRouterParsingService.decode(completion(
                "{\"in_game_name\":\"uzolius\",\"platform\":\"BEDROCK\",\"discord_user_id\":\"123456789012345678\"}"));

        assertTrue(app.complete(Set.of("123456789012345678")));
        assertEquals("uzolius", app.name());
        assertEquals("bedrock", app.edition());
        assertEquals("Bedrock", app.platform().label());
    }

    @Test
    void rejectsUnknownDiscordUser() throws IOException {
        OpenRouterParsingService.App app = OpenRouterParsingService.decode(completion(
                "{\"in_game_name\":\"Notch\",\"platform\":\"REGULAR_JAVA\",\"discord_user_id\":\"123456789012345678\"}"));

        assertFalse(app.complete(Set.of("999999999999999999")));
    }

    @Test
    void marksCrackedJava() throws IOException {
        OpenRouterParsingService.App app = OpenRouterParsingService.decode(completion(
                "{\"in_game_name\":\"Player123\",\"platform\":\"CRACKED_JAVA\",\"discord_user_id\":\"123456789012345678\"}"));

        assertTrue(app.cracked());
        assertEquals("Cracked Java", app.platform().label());
    }

    @Test
    void validatesCompleteReferralPair() throws IOException {
        OpenRouterParsingService.App app = OpenRouterParsingService.decode(completion(
                "{\"in_game_name\":\"Notch\",\"platform\":\"REGULAR_JAVA\",\"discord_user_id\":\"123456789012345678\","
                        + "\"referral_discord_name\":\"<@999999999999999999>\",\"referral_discord_user_id\":\"999999999999999999\","
                        + "\"referral_minecraft_name\":\"Referrer\"}"));

        assertTrue(app.referral());
        assertFalse(app.incompleteReferral(Set.of("123456789012345678")));
    }

    @Test
    void rejectsPartialReferralPair() throws IOException {
        OpenRouterParsingService.App app = OpenRouterParsingService.decode(completion(
                "{\"in_game_name\":\"Notch\",\"platform\":\"REGULAR_JAVA\",\"discord_user_id\":\"123456789012345678\","
                        + "\"referral_discord_name\":\"\",\"referral_discord_user_id\":\"\","
                        + "\"referral_minecraft_name\":\"Referrer\"}"));

        assertTrue(app.incompleteReferral(Set.of("123456789012345678")));
    }

    private static String completion(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices);
        return response.toString();
    }
}

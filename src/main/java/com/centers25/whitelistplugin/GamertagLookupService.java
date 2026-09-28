package com.centers25.whitelistplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class GamertagLookupService {
    private static final String JAVA_LOOKUP = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    private static final String XBOX_PROFILE = "https://profile.xboxlive.com/users/gt(%s)/profile/settings"
            + "?settings=Gamertag,GameDisplayName,ModernGamertag,ModernGamertagSuffix,UniqueModernGamertag";

    private final MicrosoftXboxAuthService authService;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    GamertagLookupService(MicrosoftXboxAuthService authService, Duration requestTimeout) {
        this.authService = authService;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    LookupResult lookup(String rawUsername, String requestedEdition) throws Exception {
        Edition edition = resolveEdition(rawUsername, requestedEdition);
        String username = rawUsername.trim();
        if (username.startsWith(".")) username = username.substring(1).trim();
        if (username.isBlank()) throw new IllegalArgumentException("The username is empty.");
        return edition == Edition.BEDROCK ? lookupBedrock(username) : lookupJava(username);
    }

    static Edition resolveEdition(String rawUsername, String requestedEdition) {
        String normalized = requestedEdition == null ? "auto" : requestedEdition.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "java" -> Edition.JAVA;
            case "bedrock" -> Edition.BEDROCK;
            case "auto" -> rawUsername.trim().startsWith(".") ? Edition.BEDROCK : Edition.JAVA;
            default -> throw new IllegalArgumentException("Unknown edition `" + normalized + "`.");
        };
    }

    private LookupResult lookupJava(String username) throws IOException, InterruptedException {
        URI uri = URI.create(JAVA_LOOKUP + pathEncode(username));
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET().build());
        JsonObject body = parseObjectOrEmpty(response.body());

        if (response.statusCode() / 100 == 2 && body.has("id") && body.has("name")) {
            LinkedHashMap<String, String> details = new LinkedHashMap<>();
            details.put("Username", body.get("name").getAsString());
            details.put("UUID", formatDashedUuid(body.get("id").getAsString()));
            return new LookupResult(Edition.JAVA, Status.EXISTS, username, details, null);
        }
        if (response.statusCode() == 404) {
            return new LookupResult(Edition.JAVA, Status.NOT_FOUND, username, Map.of(), null);
        }
        if (response.statusCode() == 400) {
            return new LookupResult(Edition.JAVA, Status.INVALID, username, Map.of(),
                    getString(body, "errorMessage", "Minecraft rejected this Java profile name."));
        }
        throw new IOException("Minecraft Services returned HTTP " + response.statusCode() + ".");
    }

    private LookupResult lookupBedrock(String gamertag) throws Exception {
        MicrosoftXboxAuthService.XboxCredential credential = authService.getOrRefreshCredential();
        URI uri = URI.create(XBOX_PROFILE.formatted(pathEncode(gamertag)));
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Authorization", "XBL3.0 x=" + credential.userHash() + ";" + credential.token())
                .header("x-xbl-contract-version", "2")
                .header("Accept", "application/json")
                .GET().build());
        JsonObject body = parseObjectOrEmpty(response.body());
        JsonArray users = body.has("profileUsers") && body.get("profileUsers").isJsonArray()
                ? body.getAsJsonArray("profileUsers") : new JsonArray();

        if (response.statusCode() / 100 == 2 && !users.isEmpty()) {
            JsonObject user = users.get(0).getAsJsonObject();
            LinkedHashMap<String, String> details = new LinkedHashMap<>();
            details.put("XUID", getString(user, "id", "unknown"));
            details.put("Gamertag", getSetting(user, "Gamertag", "unknown"));
            details.put("Display name", getSetting(user, "GameDisplayName", "unknown"));
            details.put("Modern gamertag", getSetting(user, "ModernGamertag", "unknown"));
            details.put("Modern suffix", getSetting(user, "ModernGamertagSuffix", "none"));
            details.put("Unique modern gamertag", getSetting(user, "UniqueModernGamertag", "unknown"));
            return new LookupResult(Edition.BEDROCK, Status.EXISTS, gamertag, details, null);
        }
        if (response.statusCode() == 404 || (response.statusCode() / 100 == 2 && users.isEmpty())) {
            return new LookupResult(Edition.BEDROCK, Status.NOT_FOUND, gamertag, Map.of(), null);
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new MicrosoftXboxAuthService.AuthRequiredException("Xbox rejected the saved login. Run `/auth` again.");
        }
        throw new IOException("Xbox profile lookup returned HTTP " + response.statusCode() + ".");
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonObject parseObjectOrEmpty(String body) throws IOException {
        if (body == null || body.isBlank()) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException error) {
            throw new IOException("The lookup service returned invalid JSON.", error);
        }
    }

    private static String getSetting(JsonObject profileUser, String id, String fallback) {
        if (!profileUser.has("settings") || !profileUser.get("settings").isJsonArray()) return fallback;
        for (JsonElement element : profileUser.getAsJsonArray("settings")) {
            if (!element.isJsonObject()) continue;
            JsonObject setting = element.getAsJsonObject();
            if (id.equals(getString(setting, "id", null))) return getString(setting, "value", fallback);
        }
        return fallback;
    }

    private static String getString(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static String pathEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String formatDashedUuid(String uuid) {
        if (uuid == null || !uuid.matches("[0-9a-fA-F]{32}")) return uuid;
        return uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-" + uuid.substring(12, 16)
                + "-" + uuid.substring(16, 20) + "-" + uuid.substring(20);
    }

    static boolean validCracked(String rawUsername) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        return username.matches("[A-Za-z0-9_]{3,15}");
    }

    static String crackedName(String rawUsername) {
        return "-" + rawUsername.trim();
    }

    enum Edition {
        JAVA,
        BEDROCK
    }

    enum Status { EXISTS, NOT_FOUND, INVALID }

    record LookupResult(Edition edition, Status status, String requested, Map<String, String> details, String note) {
        boolean exists() {
            return status == Status.EXISTS;
        }

        String name() {
            String value = edition == Edition.BEDROCK
                    ? details.getOrDefault("Gamertag", requested)
                    : details.getOrDefault("Username", requested);
            return (edition == Edition.BEDROCK ? "." : "") + value.replace(' ', '_');
        }

        String toDiscordMessage() {
            if (status != Status.EXISTS) return "False";
            return "True - " + WhitelistPlugin.safe(name());
        }
    }
}

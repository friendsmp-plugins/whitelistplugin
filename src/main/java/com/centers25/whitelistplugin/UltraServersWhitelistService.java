package com.centers25.whitelistplugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

final class UltraServersWhitelistService {
    private static final Pattern NAME = Pattern.compile("^[.-]?[A-Za-z0-9_ ]{1,32}$");
    private final String base;
    private final String server;
    private final String key;
    private final Duration timeout;
    private final HttpClient http;

    UltraServersWhitelistService(String base, String server, String key, Duration timeout) {
        this.base = base.replaceAll("/+$", "");
        this.server = server.trim();
        this.key = key == null ? "" : key.trim();
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    String add(String rawName) throws Exception {
        String name = rawName == null ? "" : rawName.trim();
        if (!valid(name)) throw new IllegalArgumentException("The proxy rejected the username format.");
        if (key.isBlank() || server.isBlank()) throw new IllegalStateException("UltraServers API access is not configured.");
        command(name);
        return "Command accepted.";
    }

    static boolean valid(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    private void command(String name) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("command", "whitelist add " + name);
        HttpRequest request = request("/api/client/servers/" + server + "/command")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        json(http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + key);
    }

    private static JsonObject json(HttpResponse<String> response) throws IOException {
        if (response.statusCode() / 100 != 2) throw new IOException("UltraServers returned HTTP " + response.statusCode() + ".");
        if (response.body() == null || response.body().isBlank()) return new JsonObject();
        try {
            JsonElement value = JsonParser.parseString(response.body());
            return value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException error) {
            throw new IOException("UltraServers returned invalid JSON.", error);
        }
    }

}

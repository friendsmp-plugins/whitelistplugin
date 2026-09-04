package com.centers25.whitelistplugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

final class MicrosoftXboxAuthService {
    static final String XBOX_RELYING_PARTY = "http://xboxlive.com";
    private static final String OAUTH_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
    private static final URI DEVICE_CODE_URI = URI.create("https://login.live.com/oauth20_connect.srf");
    private static final URI TOKEN_URI = URI.create("https://login.live.com/oauth20_token.srf");
    private static final URI USER_AUTH_URI = URI.create("https://user.auth.xboxlive.com/user/authenticate");
    private static final URI DEVICE_AUTH_URI = URI.create("https://device.auth.xboxlive.com/device/authenticate");
    private static final URI TITLE_AUTH_URI = URI.create("https://title.auth.xboxlive.com/title/authenticate");
    private static final URI XSTS_URI = URI.create("https://xsts.auth.xboxlive.com/xsts/authorize");
    private static final long WINDOWS_EPOCH_OFFSET_SECONDS = 11_644_473_600L;
    private static final long EXPIRY_MARGIN_SECONDS = 60;

    private final String clientId;
    private final TokenStore tokenStore;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Duration loginTimeout;
    private final Gson gson = new Gson();
    private final ReentrantLock stateLock = new ReentrantLock();

    MicrosoftXboxAuthService(String clientId, Path tokenFile, Duration requestTimeout, Duration loginTimeout) {
        this.clientId = clientId;
        this.tokenStore = new TokenStore(tokenFile);
        this.requestTimeout = requestTimeout;
        this.loginTimeout = loginTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    DeviceCode beginDeviceLogin() throws IOException, InterruptedException {
        String body = form(Map.of(
                "scope", OAUTH_SCOPE,
                "client_id", clientId,
                "response_type", "device_code"));
        HttpResponse<String> response = sendForm(DEVICE_CODE_URI, body, null);
        JsonObject json = requireSuccessJson(response, "Microsoft device login could not be started");
        long serverExpiry = getLong(json, "expires_in", loginTimeout.toSeconds());
        long effectiveExpiry = Math.min(serverExpiry, loginTimeout.toSeconds());
        long interval = Math.max(1, getLong(json, "interval", 5));
        String cookie = response.headers().allValues("set-cookie").stream()
                .map(value -> value.split(";", 2)[0])
                .reduce((left, right) -> left + "; " + right)
                .orElse(null);
        return new DeviceCode(
                requireString(json, "device_code"),
                requireString(json, "user_code"),
                json.has("verification_uri") ? json.get("verification_uri").getAsString() : "https://microsoft.com/link",
                effectiveExpiry,
                interval,
                Instant.now().getEpochSecond(),
                cookie);
    }

    XboxCredential completeDeviceLogin(DeviceCode code) throws Exception {
        stateLock.lockInterruptibly();
        try {
            TokenStore.TokenState microsoft = pollForMicrosoftToken(code);
            tokenStore.save(microsoft);
            TokenStore.TokenState.XboxToken xbox = exchangeForXbox(microsoft.accessToken());
            TokenStore.TokenState complete = microsoft.withXbox(xbox);
            tokenStore.save(complete);
            return credential(complete);
        } finally {
            stateLock.unlock();
        }
    }

    XboxCredential getOrRefreshCredential() throws Exception {
        stateLock.lockInterruptibly();
        try {
            TokenStore.TokenState state = tokenStore.load().orElseThrow(() ->
                    new AuthRequiredException("Bedrock lookup is not authenticated. Run `/auth` first."));
            long now = Instant.now().getEpochSecond();
            if (notBlank(state.xstsToken()) && notBlank(state.userHash())
                    && state.xstsExpiresAtEpochSecond() - EXPIRY_MARGIN_SECONDS > now) {
                return credential(state);
            }

            TokenStore.TokenState microsoft = state;
            if (!notBlank(state.accessToken()) || state.accessExpiresAtEpochSecond() - EXPIRY_MARGIN_SECONDS <= now) {
                if (!notBlank(state.refreshToken())) {
                    throw new AuthRequiredException("The saved Microsoft login cannot be refreshed. Run `/auth` again.");
                }
                microsoft = refreshMicrosoftToken(state);
                tokenStore.save(microsoft);
            }

            TokenStore.TokenState complete = microsoft.withXbox(exchangeForXbox(microsoft.accessToken()));
            tokenStore.save(complete);
            return credential(complete);
        } finally {
            stateLock.unlock();
        }
    }

    private TokenStore.TokenState pollForMicrosoftToken(DeviceCode code) throws Exception {
        long deadline = code.createdAtEpochSecond() + code.expiresInSeconds();
        long intervalSeconds = code.intervalSeconds();
        while (Instant.now().getEpochSecond() < deadline) {
            Thread.sleep(Duration.ofSeconds(intervalSeconds));
            String body = form(Map.of(
                    "client_id", clientId,
                    "device_code", code.deviceCode(),
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
            HttpResponse<String> response = sendForm(
                    URI.create(TOKEN_URI + "?client_id=" + urlEncode(clientId)), body, code.cookieHeader());
            JsonObject json = parseObject(response.body(), "Microsoft token response");
            if (json.has("access_token")) return stateFromMicrosoftToken(json, null);

            String error = getString(json, "error", "unknown_error");
            if ("authorization_pending".equals(error)) continue;
            if ("slow_down".equals(error)) {
                intervalSeconds += 5;
                continue;
            }
            if ("expired_token".equals(error) || "authorization_declined".equals(error)) {
                throw new AuthRequiredException(getString(json, "error_description", "Microsoft device login expired or was declined."));
            }
            throw new IOException("Microsoft device login failed: " + getString(json, "error_description", error));
        }
        throw new AuthRequiredException("Microsoft device login timed out. Run `/auth` again for a new code.");
    }

    private TokenStore.TokenState refreshMicrosoftToken(TokenStore.TokenState previous) throws IOException, InterruptedException {
        String body = form(Map.of(
                "scope", OAUTH_SCOPE,
                "client_id", clientId,
                "grant_type", "refresh_token",
                "refresh_token", previous.refreshToken()));
        HttpResponse<String> response = sendForm(TOKEN_URI, body, null);
        JsonObject json = requireSuccessJson(response, "Microsoft login refresh failed; run `/auth` again");
        return stateFromMicrosoftToken(json, previous.refreshToken());
    }

    private TokenStore.TokenState stateFromMicrosoftToken(JsonObject json, String fallbackRefreshToken) throws IOException {
        String accessToken = requireString(json, "access_token");
        String refreshToken = getString(json, "refresh_token", fallbackRefreshToken);
        if (!notBlank(refreshToken)) throw new IOException("Microsoft did not provide a refresh token; run `/auth` again.");
        long expiresAt = Instant.now().getEpochSecond() + getLong(json, "expires_in", 3600);
        return new TokenStore.TokenState(accessToken, refreshToken, expiresAt, null, null, null, 0);
    }

    private TokenStore.TokenState.XboxToken exchangeForXbox(String microsoftAccessToken) throws Exception {
        KeyPair keyPair = createEcKeyPair();
        JsonObject proofKey = createProofKey((ECPublicKey) keyPair.getPublic());

        JsonObject userProperties = object(
                "AuthMethod", "RPS",
                "SiteName", "user.auth.xboxlive.com",
                "RpsTicket", "t=" + microsoftAccessToken);
        JsonObject userRequest = object(
                "RelyingParty", "http://auth.xboxlive.com",
                "TokenType", "JWT",
                "Properties", userProperties);
        JsonObject userResponse = sendXbox(USER_AUTH_URI, userRequest, keyPair, proofKey, "2");
        String userToken = requireString(userResponse, "Token");

        JsonObject deviceProperties = object(
                "AuthMethod", "ProofOfPossession",
                "Id", "{" + UUID.randomUUID() + "}",
                "DeviceType", "Nintendo",
                "SerialNumber", "{" + UUID.randomUUID() + "}",
                "Version", "0.0.0",
                "ProofKey", proofKey);
        JsonObject deviceRequest = object(
                "Properties", deviceProperties,
                "RelyingParty", "http://auth.xboxlive.com",
                "TokenType", "JWT");
        JsonObject deviceResponse = sendXbox(DEVICE_AUTH_URI, deviceRequest, keyPair, proofKey, "1");
        String deviceToken = requireString(deviceResponse, "Token");

        JsonObject titleProperties = object(
                "AuthMethod", "RPS",
                "DeviceToken", deviceToken,
                "RpsTicket", "t=" + microsoftAccessToken,
                "SiteName", "user.auth.xboxlive.com",
                "ProofKey", proofKey);
        JsonObject titleRequest = object(
                "Properties", titleProperties,
                "RelyingParty", "http://auth.xboxlive.com",
                "TokenType", "JWT");
        JsonObject titleResponse = sendXbox(TITLE_AUTH_URI, titleRequest, keyPair, proofKey, "1");
        String titleToken = requireString(titleResponse, "Token");

        JsonArray userTokens = new JsonArray();
        userTokens.add(userToken);
        JsonObject xstsProperties = object(
                "UserTokens", userTokens,
                "DeviceToken", deviceToken,
                "TitleToken", titleToken,
                "ProofKey", proofKey,
                "SandboxId", "RETAIL");
        JsonObject xstsRequest = object(
                "RelyingParty", XBOX_RELYING_PARTY,
                "TokenType", "JWT",
                "Properties", xstsProperties);
        JsonObject xstsResponse = sendXbox(XSTS_URI, xstsRequest, keyPair, proofKey, "1");

        JsonObject claim = xstsResponse.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject();
        String userHash = requireString(claim, "uhs");
        String xuid = getString(claim, "xid", null);
        String xstsToken = requireString(xstsResponse, "Token");
        long expiresAt = parseInstantEpoch(requireString(xstsResponse, "NotAfter"));
        return new TokenStore.TokenState.XboxToken(xstsToken, userHash, xuid, expiresAt);
    }

    private JsonObject sendXbox(URI uri, JsonObject payload, KeyPair keyPair, JsonObject ignoredProofKey,
                                String contractVersion) throws IOException, InterruptedException, GeneralSecurityException {
        String body = gson.toJson(payload);
        String signature = signXboxRequest(uri, body, keyPair);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store, must-revalidate, no-cache")
                .header("x-xbl-contract-version", contractVersion)
                .header("Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject json = parseObject(response.body(), "Xbox authentication response");
        if (response.statusCode() / 100 != 2) throw xboxFailure(response, json);
        return json;
    }

    private IOException xboxFailure(HttpResponse<String> response, JsonObject json) {
        long code = getLong(json, "XErr", 0);
        String message = switch ((int) code) {
            case -2146051063 -> "This Microsoft account does not have an Xbox profile.";
            case -2146051058 -> "This Xbox account is under 18 and needs family approval.";
            default -> "Xbox authentication failed (HTTP " + response.statusCode() + (code == 0 ? "" : ", XErr " + code) + ").";
        };
        return new IOException(message);
    }

    private String signXboxRequest(URI uri, String payload, KeyPair keyPair) throws GeneralSecurityException, IOException {
        long windowsTimestamp = (Instant.now().getEpochSecond() + WINDOWS_EPOCH_OFFSET_SECONDS) * 10_000_000L;
        ByteArrayOutputStream signing = new ByteArrayOutputStream();
        writeInt(signing, 1);
        signing.write(0);
        writeLong(signing, windowsTimestamp);
        signing.write(0);
        writeNullTerminated(signing, "POST");
        writeNullTerminated(signing, uri.getRawPath());
        writeNullTerminated(signing, "");
        writeNullTerminated(signing, payload);

        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(keyPair.getPrivate());
        signer.update(signing.toByteArray());
        byte[] signature = signer.sign();
        if (signature.length != 64) throw new GeneralSecurityException("Unexpected Xbox signature length: " + signature.length);

        ByteBuffer header = ByteBuffer.allocate(12 + signature.length).order(ByteOrder.BIG_ENDIAN);
        header.putInt(1).putLong(windowsTimestamp).put(signature);
        return Base64.getEncoder().encodeToString(header.array());
    }

    private static KeyPair createEcKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static JsonObject createProofKey(ECPublicKey key) {
        JsonObject proof = new JsonObject();
        proof.addProperty("kty", "EC");
        proof.addProperty("crv", "P-256");
        proof.addProperty("x", base64UrlUnsigned(key.getW().getAffineX(), 32));
        proof.addProperty("y", base64UrlUnsigned(key.getW().getAffineY(), 32));
        proof.addProperty("alg", "ES256");
        proof.addProperty("use", "sig");
        return proof;
    }

    static String base64UrlUnsigned(BigInteger value, int width) {
        byte[] signed = value.toByteArray();
        byte[] fixed = new byte[width];
        int sourceStart = Math.max(0, signed.length - width);
        int length = Math.min(signed.length, width);
        System.arraycopy(signed, sourceStart, fixed, width - length, length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
    }

    private HttpResponse<String> sendForm(URI uri, String body, String cookie) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");
        if (cookie != null && !cookie.isBlank()) builder.header("Cookie", cookie);
        return httpClient.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonObject requireSuccessJson(HttpResponse<String> response, String context) throws IOException {
        JsonObject json = parseObject(response.body(), context);
        if (response.statusCode() / 100 != 2) {
            throw new IOException(context + ": " + getString(json, "error_description", "HTTP " + response.statusCode()));
        }
        return json;
    }

    private static JsonObject parseObject(String body, String context) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(body == null || body.isBlank() ? "{}" : body);
            if (!parsed.isJsonObject()) throw new IOException(context + " was not a JSON object.");
            return parsed.getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException(context + " was not valid JSON.", error);
        }
    }

    private static JsonObject object(Object... pairs) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i];
            Object value = pairs[i + 1];
            if (value == null) object.add(key, null);
            else if (value instanceof JsonElement element) object.add(key, element);
            else if (value instanceof Number number) object.addProperty(key, number);
            else if (value instanceof Boolean bool) object.addProperty(key, bool);
            else object.addProperty(key, value.toString());
        }
        return object;
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requireString(JsonObject object, String key) throws IOException {
        String value = getString(object, key, null);
        if (!notBlank(value)) throw new IOException("Authentication response omitted `" + key + "`.");
        return value;
    }

    private static String getString(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static long getLong(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        try {
            return value == null || value.isJsonNull() ? fallback : value.getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long parseInstantEpoch(String value) throws IOException {
        try {
            return Instant.parse(value).getEpochSecond();
        } catch (DateTimeParseException error) {
            throw new IOException("Xbox returned an invalid expiry timestamp.", error);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void writeInt(ByteArrayOutputStream output, int value) throws IOException {
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array());
    }

    private static void writeLong(ByteArrayOutputStream output, long value) throws IOException {
        output.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array());
    }

    private static void writeNullTerminated(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write(0);
    }

    private static XboxCredential credential(TokenStore.TokenState state) {
        return new XboxCredential(state.userHash(), state.xstsToken(), state.xuid(), state.xstsExpiresAtEpochSecond());
    }

    record DeviceCode(
            String deviceCode,
            String userCode,
            String verificationUri,
            long expiresInSeconds,
            long intervalSeconds,
            long createdAtEpochSecond,
            String cookieHeader
    ) { }

    record XboxCredential(String userHash, String token, String xuid, long expiresAtEpochSecond) { }

    static final class AuthRequiredException extends Exception {
        AuthRequiredException(String message) {
            super(message);
        }
    }
}

package com.centers25.whitelistplugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

final class TokenStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;

    TokenStore(Path path) {
        this.path = path;
    }

    Optional<TokenState> load() throws IOException {
        if (!Files.isRegularFile(path)) return Optional.empty();
        String json = Files.readString(path, StandardCharsets.UTF_8);
        return Optional.ofNullable(GSON.fromJson(json, TokenState.class));
    }

    void save(TokenState state) throws IOException {
        Files.createDirectories(path.getParent());
        setOwnerOnlyDirectory(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(state), StandardCharsets.UTF_8);
        setOwnerOnlyFile(temporary);
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
        setOwnerOnlyFile(path);
    }

    private static void setOwnerOnlyDirectory(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException | IOException ignored) { }
    }

    private static void setOwnerOnlyFile(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) { }
    }

    record TokenState(
            String accessToken,
            String refreshToken,
            long accessExpiresAtEpochSecond,
            String xstsToken,
            String userHash,
            String xuid,
            long xstsExpiresAtEpochSecond
    ) {
        TokenState withXbox(XboxToken token) {
            return new TokenState(accessToken, refreshToken, accessExpiresAtEpochSecond,
                    token.token(), token.userHash(), token.xuid(), token.expiresAtEpochSecond());
        }

        record XboxToken(String token, String userHash, String xuid, long expiresAtEpochSecond) { }
    }
}

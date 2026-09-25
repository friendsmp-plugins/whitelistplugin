package com.centers25.whitelistplugin;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UltraServersWhitelistServiceTest {
    @Test
    void acceptsSupportedNamesAndRejectsCommandInjection() {
        assertTrue(UltraServersWhitelistService.valid("Notch"));
        assertTrue(UltraServersWhitelistService.valid(".Uzolius"));
        assertTrue(UltraServersWhitelistService.valid("-Player_123"));
        assertFalse(UltraServersWhitelistService.valid("Player; stop"));
        assertFalse(UltraServersWhitelistService.valid("Player\nstop"));
    }

    @Test
    void sendsAuthenticatedConsoleCommandToSelectedServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> command = new AtomicReference<>();
        server.createContext("/api/client/servers/f1fd5062/command", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            command.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            var service = new UltraServersWhitelistService(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "f1fd5062", "test-key", Duration.ofSeconds(5));
            assertEquals("Command accepted.", service.add("Notch"));
            assertEquals("{\"command\":\"whitelist add Notch\"}", command.get());
        } finally {
            server.stop(0);
        }
    }
}

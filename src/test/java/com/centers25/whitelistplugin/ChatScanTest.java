package com.centers25.whitelistplugin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatScanTest {
    @Test
    void deduplicatesAndFiltersSpam() {
        String form = "In-Game Name/Gamertag: Notch\nPlatform: Premium Java";
        ChatScan.Scan scan = ChatScan.build(List.of(
                item("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                item(form),
                item(form)
        ), 1500, 8000);

        assertEquals(1, scan.transcript().split("In-Game Name/Gamertag", -1).length - 1);
        assertTrue(scan.users().contains("123456789012345678"));
    }

    @Test
    void dropsOversizedMessagesAndKeepsMentions() {
        ChatScan.Item form = new ChatScan.Item("999999999999999999", "Form Bot", true,
                Map.of("123456789012345678", "Applicant"), "", "", "In-Game Name/Gamertag: Alex\nPlatform: Bedrock");
        ChatScan.Scan scan = ChatScan.build(List.of(form, item("x".repeat(1600))), 1500, 8000);

        assertFalse(scan.empty());
        assertEquals(java.util.Set.of("123456789012345678"), scan.users());
    }

    @Test
    void preservesReplyIdentity() {
        ChatScan.Item answer = new ChatScan.Item("999999999999999999", "Helper", false, Map.of(),
                "123456789012345678", "Applicant", "They use Bedrock");
        ChatScan.Scan scan = ChatScan.build(List.of(answer), 1500, 8000);

        assertTrue(scan.transcript().contains("reply_to_user=123456789012345678:Applicant"));
        assertTrue(scan.users().contains("123456789012345678"));
    }

    private static ChatScan.Item item(String text) {
        return new ChatScan.Item("123456789012345678", "Applicant", false, Map.of(), "", "", text);
    }
}

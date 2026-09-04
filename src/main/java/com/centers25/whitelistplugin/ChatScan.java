package com.centers25.whitelistplugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ChatScan {
    private static final Pattern URL = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern RUN = Pattern.compile("(.)\\1{14,}", Pattern.DOTALL);
    private static final Pattern SPACE = Pattern.compile("\\s+");

    static Scan build(List<Item> newest, int maxMessage, int maxInput) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> users = new LinkedHashSet<>();
        List<String> blocks = new ArrayList<>();
        int length = 0;
        for (Item item : newest) {
            String text = item.text().trim();
            String key = SPACE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ");
            if (text.isBlank() || spam(text, maxMessage) || !seen.add(key)) continue;
            String block = block(item, text);
            if (length + block.length() > maxInput) continue;
            blocks.add(block);
            length += block.length();
            if (!item.bot()) users.add(item.authorId());
            users.addAll(item.mentions().keySet());
            if (!item.replyId().isBlank()) users.add(item.replyId());
        }
        Collections.reverse(blocks);
        return new Scan(String.join("\n\n", blocks), Set.copyOf(users));
    }

    static boolean spam(String text, int max) {
        if (text.length() > max || RUN.matcher(text).find()) return true;
        Matcher links = URL.matcher(text);
        int count = 0;
        while (links.find()) if (++count > 3) return true;
        if (text.length() < 80) return false;
        long symbols = text.chars().filter(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c) && c != '_' && c != ':' && c != '|').count();
        return symbols * 100 / text.length() > 45;
    }

    private static String block(Item item, String text) {
        StringBuilder meta = new StringBuilder("[author_id=").append(item.authorId())
                .append("; author=").append(clean(item.author())).append("; bot=").append(item.bot());
        if (!item.mentions().isEmpty()) {
            meta.append("; mentioned_users=");
            item.mentions().forEach((id, name) -> meta.append(id).append(':').append(clean(name)).append(','));
        }
        if (!item.replyId().isBlank()) {
            meta.append("; reply_to_user=").append(item.replyId()).append(':').append(clean(item.replyName()));
        }
        return meta.append("]\n").append(text).toString();
    }

    private static String clean(String value) {
        return value == null ? "unknown" : value.replace("[", "(").replace("]", ")").replace(";", ",");
    }

    record Item(String authorId, String author, boolean bot, Map<String, String> mentions,
                String replyId, String replyName, String text) { }

    record Scan(String transcript, Set<String> users) {
        boolean empty() {
            return transcript.isBlank() || users.isEmpty();
        }
    }
}

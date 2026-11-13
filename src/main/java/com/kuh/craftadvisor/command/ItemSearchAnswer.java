package com.kuh.craftadvisor.command;

import java.util.List;

/**
 * The text side of {@link ItemSearch}: what the AI is asked, and the uids its answer names. Whether a uid is a real
 * item is checked by code against JEI, never taken from the answer. No Minecraft classes, so it is unit-tested.
 */
final class ItemSearchAnswer {
    private static final String TRIM = "\"'`*.,;()[]<>";

    private ItemSearchAnswer() {
    }

    /** Sent as the user message; the player's words are quoted, not followed. */
    static String instruction(String query) {
        return """
                The player wants to add one item to a crafting goal list and wrote: "%s"
                This is not an exact item name. It may describe the item, use a synonym, or be a wrong translation.
                Find the item with search_items: try English names, synonyms and shorter words.
                Reply with only the uid of the best matching item from the search results, or NONE if no result \
                is an item that fits.""".formatted(query.replace("\"", "'"));
    }

    /** Words of the answer that look like a uid ({@code namespace:path}), in order; empty for NONE. */
    static List<String> uids(String answer) {
        return List.of(answer.strip().split("\\s+")).stream()
                .map(ItemSearchAnswer::trim)
                .filter(word -> word.indexOf(':') > 0 && !word.endsWith(":"))
                .toList();
    }

    private static String trim(String word) {
        int start = 0;
        int end = word.length();
        while (start < end && TRIM.indexOf(word.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && TRIM.indexOf(word.charAt(end - 1)) >= 0) {
            end--;
        }
        return word.substring(start, end);
    }
}

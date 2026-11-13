package com.kuh.craftadvisor.chat;

/** Text from an AI or a server, made safe for one chat line. Pure Java so it can be tested. */
public final class ChatText {
    /** Server error messages are cut to this length; AI answers are bounded by their token limit instead. */
    public static final int ERROR_MAX_CHARS = 200;
    private static final char FORMAT_CODE = '§';

    private ChatText() {
    }

    /**
     * Without {@code §} format codes (which could fake a system line in chat) and control characters (line breaks
     * become spaces), at most {@code maxChars} characters.
     */
    public static String clean(String text, int maxChars) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            if (Character.isISOControl(c)) {
                out.append(' ');
            } else if (c != FORMAT_CODE) {
                out.appendCodePoint(c);
            }
        });
        String cleaned = out.toString().strip();
        return cleaned.length() > maxChars ? cleaned.substring(0, maxChars) + "..." : cleaned;
    }
}

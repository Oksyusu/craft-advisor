package com.kuh.craftadvisor.command;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text of {@code /ai add}: an item name with an optional count at the end, written {@code x4}, {@code ×4}
 * or {@code 4개}. Parsed by hand rather than with a Brigadier integer argument, which would be ambiguous next
 * to the free-text name. No Minecraft classes, so it is unit-tested directly.
 */
record GoalInput(String name, int amount) {
    static final int MIN_AMOUNT = 1;
    static final int MAX_AMOUNT = 999;
    private static final int DEFAULT_AMOUNT = 1;
    /** Longer digit runs are out of range anyway; this keeps parseInt from overflowing. */
    private static final int MAX_DIGITS = 4;
    /**
     * {@code x4} may touch a name that does not end in a Latin letter, digit, {@code _} or {@code :} ("피스톤x10",
     * not "box10" or "mod:tier_x2"); {@code ×4} and {@code 4개} may touch any name ("피스톤10개").
     */
    private static final Pattern COUNT = Pattern.compile(
            "(?:(?<![A-Za-z0-9_:])[xX](\\d+)|×\\s?(\\d+)|(?<!\\d)(\\d+)\\s?개)\\s*$");

    /** Empty when a count is written but outside {@value #MIN_AMOUNT}..{@value #MAX_AMOUNT}. The name may be blank. */
    static Optional<GoalInput> parse(String text) {
        String stripped = text.strip();
        Matcher matcher = COUNT.matcher(stripped);
        if (!matcher.find()) {
            return Optional.of(new GoalInput(stripped, DEFAULT_AMOUNT));
        }
        String digits = matcher.group(1) != null ? matcher.group(1)
                : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        if (digits.length() > MAX_DIGITS) {
            return Optional.empty();
        }
        int amount = Integer.parseInt(digits);
        if (amount < MIN_AMOUNT || amount > MAX_AMOUNT) {
            return Optional.empty();
        }
        return Optional.of(new GoalInput(stripped.substring(0, matcher.start()).strip(), amount));
    }
}

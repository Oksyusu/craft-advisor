package com.kuh.craftadvisor;

import net.neoforged.neoforge.common.ModConfigSpec;

// Stored in <game dir>/config/craftadvisor-client.toml, never in the repository.
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> API_KEY = BUILDER
            .comment("Anthropic API key used for /ai. Leave empty to use only exact item names without AI.")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> MODEL = BUILDER
            .comment("Claude model id.")
            .define("model", "claude-opus-5-5");

    public static final ModConfigSpec.IntValue MAX_CANDIDATES = BUILDER
            .comment("How many JEI items are offered to the AI when it picks the target item.")
            .defineInRange("maxCandidates", 40, 5, 200);

    public static final ModConfigSpec.IntValue ADVICE_MAX_TOKENS = BUILDER
            .comment("Maximum tokens for an advice or free-question answer.")
            .defineInRange("adviceMaxTokens", 2000, 256, 16000);

    static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }
}

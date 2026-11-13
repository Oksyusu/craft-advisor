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

    public static final ModConfigSpec.IntValue MAX_TOOL_CALLS = BUILDER
            .comment("How many game-data lookups (tool calls) the AI may make for one free question.")
            .defineInRange("maxToolCalls", 8, 1, 20);

    public static final ModConfigSpec.BooleanValue SHARE_INVENTORY = BUILDER
            .comment("Let the AI look up your inventory in free questions. When false, that lookup is not offered, and"
                    + " advice and the AI's missing-material lookups get total amounts instead of what is missing.")
            .define("shareInventory", true);

    public static final ModConfigSpec.BooleanValue SHARE_HISTORY = BUILDER
            .comment("Send the recent /ai conversation with free questions. Earlier answers may mention your inventory;"
                    + " restart the game to clear the conversation after turning shareInventory off.")
            .define("shareHistory", true);

    public static final ModConfigSpec.ConfigValue<String> CONSENTED_HOST = BUILDER
            .comment("AI host you agreed to send requests to (set by /ai consent). Empty or a different host asks again.")
            .define("consentedHost", "");

    static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }
}

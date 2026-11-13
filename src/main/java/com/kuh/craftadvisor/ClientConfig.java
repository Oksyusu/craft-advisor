package com.kuh.craftadvisor;

import com.kuh.craftadvisor.ai.ProviderSettings;

import net.neoforged.neoforge.common.ModConfigSpec;

// Stored in <game dir>/config/craftadvisor-client.toml, never in the repository.
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> API_KEY = BUILDER
            .comment("API key of the AI provider used for /ai. Leave empty to use only exact item names without AI"
                    + " (ollama needs no key).")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> MODEL = BUILDER
            .comment("Model id. The default is a Claude model; set your model's name for openai or ollama.")
            .define("model", ProviderSettings.DEFAULT_MODEL);

    public static final ModConfigSpec.ConfigValue<String> PROVIDER = BUILDER
            .comment("AI provider: anthropic, openai (or any OpenAI-compatible server), ollama, or offline"
                    + " (no AI and no network; exact item names only).")
            .define("provider", "anthropic",
                    value -> value instanceof String text && ProviderSettings.Kind.parse(text).isPresent());

    public static final ModConfigSpec.ConfigValue<String> BASE_URL = BUILDER
            .comment("API address. Empty uses the provider's default (anthropic https://api.anthropic.com/v1,"
                    + " openai https://api.openai.com/v1, ollama http://localhost:11434/v1). Must start with https://;"
                    + " http:// is allowed only for localhost, 127.0.0.1 and ::1.")
            .define("baseUrl", "");

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

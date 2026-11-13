package com.kuh.craftadvisor.ai;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Which AI the player chose (config {@code provider}, {@code baseUrl}, {@code apiKey}, {@code model}) and whether
 * it can be used. Pure Java: no request is ever built from settings whose {@link #status()} is not READY.
 */
public record ProviderSettings(Kind kind, String baseUrl, String apiKey, String model, int answerMaxTokens) {
    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final int FINGERPRINT_BYTES = 6;

    public enum Kind {
        ANTHROPIC("https://api.anthropic.com/v1"),
        OPENAI("https://api.openai.com/v1"),
        /** Ollama's OpenAI-compatible endpoint; no key needed. */
        OLLAMA("http://localhost:11434/v1"),
        /** No AI and no network: exact names only. */
        OFFLINE("");

        private final String defaultBaseUrl;

        Kind(String defaultBaseUrl) {
            this.defaultBaseUrl = defaultBaseUrl;
        }

        public String defaultBaseUrl() {
            return defaultBaseUrl;
        }

        /** The config value ({@code anthropic}, {@code openai}, {@code ollama}, {@code offline}), any case. */
        public static Optional<Kind> parse(String value) {
            String name = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.name().equals(name)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /** Why the AI cannot be used, checked in this order; READY means requests may be sent. */
    public enum Status { READY, OFFLINE, BAD_BASE_URL, NO_KEY, NO_MODEL }

    public ProviderSettings {
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null ? "" : model.strip();
    }

    /** Settings from raw config values; an unknown provider reads as anthropic (the config validator rejects it). */
    public static ProviderSettings of(String provider, String baseUrl, String apiKey, String model, int answerMaxTokens) {
        return new ProviderSettings(Kind.parse(provider).orElse(Kind.ANTHROPIC), baseUrl, apiKey, model, answerMaxTokens);
    }

    /** The configured base URL, or the provider's default when it is empty; empty when not allowed. */
    public Optional<URI> baseUri() {
        return checkBaseUrl(baseUrl.isEmpty() ? kind.defaultBaseUrl() : baseUrl);
    }

    /**
     * {@code https://}, or {@code http://} to this computer only: anything else would send the key unencrypted.
     * A trailing {@code /} is dropped so paths can be appended.
     */
    public static Optional<URI> checkBaseUrl(String url) {
        String trimmed = url == null ? "" : url.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (java.net.URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            return Optional.empty();
        }
        boolean allowed = scheme.equals("https") || scheme.equals("http") && isLocalHost(uri.getHost());
        return allowed ? Optional.of(uri) : Optional.empty();
    }

    public static boolean isLocalHost(String host) {
        return host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    /** Where requests go (shown in the consent notice and preview); empty when offline or the URL is not allowed. */
    public String host() {
        return kind == Kind.OFFLINE ? "" : baseUri().map(URI::getHost).orElse("");
    }

    /**
     * What /ai consent records: the host and a short fingerprint of the key, never the key itself. A consent
     * written into a shared config (a modpack) stops matching once the player puts in their own key.
     */
    public String consentId() {
        return host() + "#" + keyFingerprint(apiKey);
    }

    static String keyFingerprint(String key) {
        if (key == null || key.isEmpty()) {
            return "nokey";
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, FINGERPRINT_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from the JDK", e);
        }
    }

    /** Requests stay on this computer, so no consent is needed. */
    public boolean isLocal() {
        return isLocalHost(host());
    }

    public Status status() {
        if (kind == Kind.OFFLINE) {
            return Status.OFFLINE;
        }
        if (baseUri().isEmpty()) {
            return Status.BAD_BASE_URL;
        }
        if (apiKey.isEmpty() && kind != Kind.OLLAMA) {
            return Status.NO_KEY;
        }
        if (kind != Kind.ANTHROPIC && (model.isEmpty() || model.equals(DEFAULT_MODEL))) {
            return Status.NO_MODEL;
        }
        return Status.READY;
    }

    /** The provider for READY settings. */
    public AiProvider create() {
        if (status() != Status.READY) {
            throw new IllegalStateException("AI provider is not usable: " + status());
        }
        URI base = baseUri().orElseThrow();
        return switch (kind) {
            case ANTHROPIC -> new ClaudeProvider(base, apiKey, model, answerMaxTokens, AiHttp.JDK);
            case OPENAI, OLLAMA -> new OpenAiCompatibleProvider(base, apiKey, model, answerMaxTokens, AiHttp.JDK);
            case OFFLINE -> throw new IllegalStateException("offline");
        };
    }

    /** Never shows the key (records print every field by default). */
    @Override
    public String toString() {
        return "ProviderSettings[kind=" + kind + ", baseUrl=" + baseUrl + ", model=" + model + "]";
    }
}

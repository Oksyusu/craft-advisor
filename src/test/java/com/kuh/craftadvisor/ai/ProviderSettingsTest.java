package com.kuh.craftadvisor.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ProviderSettingsTest {
    private static final String KEY = "sk-test-FAKE-KEY-0123456789";

    @Test
    void httpsAndLocalHttpBaseUrlsAreAllowed() {
        assertTrue(ProviderSettings.checkBaseUrl("https://x").isPresent());
        assertTrue(ProviderSettings.checkBaseUrl("http://localhost:11434/v1").isPresent());
        assertTrue(ProviderSettings.checkBaseUrl("http://127.0.0.1:1234/v1").isPresent());
        assertTrue(ProviderSettings.checkBaseUrl("http://[::1]:11434/v1").isPresent());
    }

    @Test
    void remotePlainHttpAndMalformedBaseUrlsAreRejected() {
        assertEquals(Optional.empty(), ProviderSettings.checkBaseUrl("http://example.com"));
        assertEquals(Optional.empty(), ProviderSettings.checkBaseUrl("http://192.168.0.5:11434/v1"));
        assertEquals(Optional.empty(), ProviderSettings.checkBaseUrl("ftp://example.com"));
        assertEquals(Optional.empty(), ProviderSettings.checkBaseUrl("api.openai.com/v1"));
        assertEquals(Optional.empty(), ProviderSettings.checkBaseUrl("https://x/v1?key=1"));
        assertEquals(Optional.empty(), ProviderSettings.checkBaseUrl("not a url"));
    }

    @Test
    void trailingSlashIsDroppedSoPathsCanBeAppended() {
        assertEquals(URI.create("https://x/v1"), ProviderSettings.checkBaseUrl("https://x/v1/").orElseThrow());
    }

    @Test
    void emptyBaseUrlUsesTheProviderDefault() {
        assertEquals("api.anthropic.com", settings("anthropic", "", KEY, "claude-opus-5-5").host());
        assertEquals("api.openai.com", settings("openai", "", KEY, "gpt-x").host());
        assertEquals(URI.create("http://localhost:11434/v1"), settings("ollama", "", "", "llama").baseUri().orElseThrow());
    }

    @Test
    void existingConfigWithoutProviderKeysUsesClaudeAsBefore() {
        // A config file without "provider" and "baseUrl" gets the defaults "anthropic" and "".
        ProviderSettings settings = settings("anthropic", "", KEY, ProviderSettings.DEFAULT_MODEL);

        assertEquals(ProviderSettings.Status.READY, settings.status());
        assertEquals("api.anthropic.com", settings.host());
        assertFalse(settings.isLocal());
        assertInstanceOf(ClaudeProvider.class, settings.create());
    }

    @Test
    void ollamaNeedsNoKeyAndStaysLocal() {
        ProviderSettings settings = settings("ollama", "", "", "qwen3:8b");

        assertEquals(ProviderSettings.Status.READY, settings.status());
        assertTrue(settings.isLocal());
        assertInstanceOf(OpenAiCompatibleProvider.class, settings.create());
    }

    @Test
    void statusExplainsWhyTheAiCannotBeUsed() {
        assertEquals(ProviderSettings.Status.OFFLINE, settings("offline", "", KEY, "m").status());
        assertEquals("", settings("offline", "", KEY, "m").host());
        assertEquals(ProviderSettings.Status.NO_KEY, settings("anthropic", "", " ", "claude-opus-5-5").status());
        assertEquals(ProviderSettings.Status.NO_KEY, settings("openai", "", "", "gpt-x").status());
        assertEquals(ProviderSettings.Status.BAD_BASE_URL, settings("openai", "http://example.com/v1", KEY, "gpt-x").status());
        assertEquals("", settings("openai", "http://example.com/v1", KEY, "gpt-x").host());
        assertEquals(ProviderSettings.Status.NO_MODEL, settings("openai", "", KEY, "claude-opus-5-5").status());
        assertEquals(ProviderSettings.Status.NO_MODEL, settings("ollama", "", "", "").status());
    }

    @Test
    void providerNamesParseInAnyCase() {
        assertEquals(Optional.of(ProviderSettings.Kind.OLLAMA), ProviderSettings.Kind.parse(" Ollama "));
        assertEquals(Optional.empty(), ProviderSettings.Kind.parse("gemini"));
    }

    @Test
    void toStringNeverShowsTheKey() {
        assertFalse(settings("openai", "", KEY, "gpt-x").toString().contains(KEY));
    }

    private static ProviderSettings settings(String provider, String baseUrl, String key, String model) {
        return ProviderSettings.of(provider, baseUrl, key, model, 2000);
    }
}

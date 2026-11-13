package com.kuh.craftadvisor.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Providers over a fake transport: what goes on the wire and how replies are handled. */
class ProviderTransportTest {
    private static final String KEY = "sk-test-FAKE-KEY-0123456789";
    private static final URI OLLAMA = URI.create("http://localhost:11434/v1");
    private static final OutgoingRequest PICK = OutgoingRequest.pick("localhost", "piston",
            List.of("minecraft:piston | Piston"));

    private record Sent(URI endpoint, Map<String, String> headers, String json) {
        JsonArray messages() {
            return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("messages");
        }
    }

    /** Answers with the scripted 200 bodies in order and records each request. */
    private static final class FakeTransport implements AiHttp.Transport {
        private final Deque<String> bodies;
        final List<Sent> sent = new ArrayList<>();

        FakeTransport(String... bodies) {
            this.bodies = new ArrayDeque<>(List.of(bodies));
        }

        @Override
        public AiHttp.Response post(URI endpoint, Map<String, String> headers, String json) {
            sent.add(new Sent(endpoint, Map.copyOf(headers), json));
            return new AiHttp.Response(200, bodies.removeFirst());
        }
    }

    private static String content(String text) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", text);
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject body = new JsonObject();
        body.add("choices", choices);
        return body.toString();
    }

    private static OpenAiCompatibleProvider openAi(String key, FakeTransport transport) {
        return new OpenAiCompatibleProvider(OLLAMA, key, "qwen3:8b", 2000, transport);
    }

    @Test
    void validPickNeedsOneRequest() {
        FakeTransport transport = new FakeTransport(content("{\"intent\":\"craft\",\"itemId\":\"minecraft:piston\",\"reason\":\"r\"}"));

        ItemPick pick = openAi("", transport).pickItem(PICK).join();

        assertTrue(pick.wantsCraft());
        assertEquals("minecraft:piston", pick.itemId());
        assertEquals(1, transport.sent.size());
        assertEquals(URI.create("http://localhost:11434/v1/chat/completions"), transport.sent.get(0).endpoint());
    }

    @Test
    void badPickIsAskedOnceMoreWithTheProblem() {
        FakeTransport transport = new FakeTransport(content("Sure! The piston."),
                content("```json\n{\"intent\":\"craft\",\"itemId\":\"minecraft:piston\",\"reason\":\"r\"}\n```"));

        ItemPick pick = openAi("", transport).pickItem(PICK).join();

        assertEquals("minecraft:piston", pick.itemId());
        assertEquals(2, transport.sent.size());
        JsonArray retry = transport.sent.get(1).messages();
        assertEquals("Sure! The piston.", retry.get(retry.size() - 2).getAsJsonObject().get("content").getAsString());
        assertTrue(retry.get(retry.size() - 1).getAsJsonObject().get("content").getAsString().contains("not a JSON object"));
    }

    @Test
    void secondBadPickBecomesAQuestion() {
        FakeTransport transport = new FakeTransport(content("not json"), content("{\"intent\":\"maybe\"}"));

        ItemPick pick = openAi("", transport).pickItem(PICK).join();

        assertEquals("question", pick.intent());
        assertFalse(pick.wantsCraft());
        assertEquals(2, transport.sent.size());
    }

    @Test
    void keyGoesOnlyInTheAuthorizationHeader() {
        FakeTransport transport = new FakeTransport(content("Mix it."));

        openAi(KEY, transport).answer(PICK).join();

        Sent sent = transport.sent.get(0);
        assertEquals("Bearer " + KEY, sent.headers().get("authorization"));
        assertFalse(sent.json().contains(KEY));
    }

    @Test
    void noKeySendsNoAuthorizationHeader() {
        FakeTransport transport = new FakeTransport(content("Mix it."));

        openAi("", transport).answer(PICK).join();

        assertFalse(transport.sent.get(0).headers().containsKey("authorization"));
    }

    @Test
    void claudeKeepsItsEndpointAndHeaders() {
        FakeTransport transport = new FakeTransport("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");
        URI base = ProviderSettings.of("anthropic", "", KEY, ProviderSettings.DEFAULT_MODEL, 2000).baseUri().orElseThrow();
        ClaudeProvider claude = new ClaudeProvider(base, KEY, ProviderSettings.DEFAULT_MODEL, 2000, transport);

        assertEquals("ok", claude.answer(PICK).join());

        Sent sent = transport.sent.get(0);
        assertEquals(URI.create("https://api.anthropic.com/v1/messages"), sent.endpoint());
        assertEquals(KEY, sent.headers().get("x-api-key"));
        assertEquals("2026-10-21", sent.headers().get("anthropic-version"));
        assertEquals("server-side-fallback-2026-07-01", sent.headers().get("anthropic-beta"));
        assertFalse(sent.json().contains(KEY));
    }
}

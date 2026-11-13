package com.kuh.craftadvisor.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class OpenAiFormatTest {
    private static final OpenAiCompatibleProvider PROVIDER = new OpenAiCompatibleProvider(
            URI.create("https://api.openai.com/v1"), "key", "gpt-x", 2000, (uri, headers, json) -> {
                throw new AssertionError("no request expected");
            });
    private static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec("search_items", "search", JsonParser.parseString("{\"type\":\"object\"}").getAsJsonObject()),
            new ToolSpec("get_recipes", "recipes", JsonParser.parseString("{\"type\":\"object\"}").getAsJsonObject()));
    private static final String TWO_CALLS = """
            {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"refusal":null,
              "annotations":[],"tool_calls":[
              {"id":"call_a","type":"function","function":{"name":"search_items","arguments":"{\\"query\\":\\"alloy\\"}"}},
              {"id":"call_b","type":"function","function":{"name":"get_recipes","arguments":"{\\"uid\\": "}}]}}]}""";

    @Test
    void pickRequestSendsTheSystemPromptAndAJsonSchema() {
        JsonObject body = PROVIDER.pickBody(OutgoingRequest.pick("api.openai.com", "piston", List.of("minecraft:piston | Piston")));

        assertEquals("gpt-x", body.get("model").getAsString());
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals("system", role(messages, 0));
        assertEquals(Prompts.PICK, messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("user", role(messages, 1));
        assertTrue(messages.get(1).getAsJsonObject().get("content").getAsString().contains("minecraft:piston | Piston"));
        JsonObject format = body.getAsJsonObject("response_format");
        assertEquals("json_schema", format.get("type").getAsString());
        JsonObject schema = format.getAsJsonObject("json_schema").getAsJsonObject("schema");
        assertEquals(List.of("intent", "itemId", "reason"),
                schema.getAsJsonArray("required").asList().stream().map(e -> e.getAsString()).toList());
        assertFalse(body.has("tools"));
    }

    @Test
    void adviceRequestIsPlainMessages() {
        OutgoingRequest request = new OutgoingRequest("h", OutgoingRequest.Route.ADVICE, List.of(),
                List.of(new OutgoingRequest.Section("request", "Player request: piston")), List.of(), List.of());

        JsonObject body = PROVIDER.answerBody(request);

        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(Prompts.ADVICE, messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("Player request: piston", messages.get(1).getAsJsonObject().get("content").getAsString());
        assertEquals(2000, body.get("max_completion_tokens").getAsInt());
        assertFalse(body.has("response_format"));
        assertFalse(body.has("tools"));
    }

    @Test
    void converseRequestListsToolsAsFunctions() {
        JsonObject body = PROVIDER.converseBody(List.of(new AgentMessage.User("q")), TOOLS, true);

        JsonObject first = body.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("function", first.get("type").getAsString());
        assertEquals("search_items", first.getAsJsonObject("function").get("name").getAsString());
        assertEquals("search", first.getAsJsonObject("function").get("description").getAsString());
        assertEquals("object", first.getAsJsonObject("function").getAsJsonObject("parameters").get("type").getAsString());
        assertEquals("auto", body.get("tool_choice").getAsString());
        assertEquals(Prompts.CHAT, body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void toolsOffKeepsTheToolsAndEchoesTheCallsWithToolMessages() {
        AgentReply reply = OpenAiCompatibleProvider.replyOf(TWO_CALLS);
        List<AgentMessage> messages = List.of(new AgentMessage.User("q"), reply.message(), new AgentMessage.ToolResults(List.of(
                ToolResult.ok("call_a", "[]"), ToolResult.error("call_b", "bad arguments"))));

        JsonObject body = PROVIDER.converseBody(messages, TOOLS, false);

        assertEquals("none", body.get("tool_choice").getAsString());
        assertEquals(2, body.getAsJsonArray("tools").size());
        JsonArray sent = body.getAsJsonArray("messages");
        assertEquals(5, sent.size()); // system, user, assistant, two tool results
        JsonObject assistant = sent.get(2).getAsJsonObject();
        assertEquals(reply.message().providerRaw(), assistant);
        assertFalse(assistant.has("annotations"));
        assertFalse(assistant.has("refusal"));
        JsonObject result = sent.get(4).getAsJsonObject();
        assertEquals("tool", result.get("role").getAsString());
        assertEquals("call_b", result.get("tool_call_id").getAsString());
        assertEquals("Error: bad arguments", result.get("content").getAsString());
    }

    @Test
    void plainAnswerIsTheMessageContent() {
        String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Mix it.\"}}]}";

        AgentReply reply = OpenAiCompatibleProvider.replyOf(body);

        assertFalse(reply.wantsTools());
        assertEquals("Mix it.", reply.message().text());
        assertEquals("Mix it.", OpenAiCompatibleProvider.textOf(body));
    }

    @Test
    void severalToolCallsAreReadInOrderAndNullContentIsEmptyText() {
        AgentReply reply = OpenAiCompatibleProvider.replyOf(TWO_CALLS);

        assertTrue(reply.wantsTools());
        assertEquals("", reply.message().text());
        assertEquals(List.of("call_a", "call_b"), reply.message().calls().stream().map(ToolCall::id).toList());
        assertEquals("alloy", reply.message().calls().get(0).input().get("query").getAsString());
        assertTrue(reply.message().providerRaw().getAsJsonObject().get("content").isJsonNull());
        String echoedArguments = reply.message().providerRaw().getAsJsonObject().getAsJsonArray("tool_calls").get(1)
                .getAsJsonObject().getAsJsonObject("function").get("arguments").getAsString();
        assertEquals("{\"uid\": ", echoedArguments);
    }

    @Test
    void brokenArgumentsGiveANullInputThatTheLoopAnswersWithAnError() {
        AgentReply reply = OpenAiCompatibleProvider.replyOf(TWO_CALLS);
        assertNull(reply.message().calls().get(1).input());
        List<List<AgentMessage>> requests = new ArrayList<>();
        AiProvider provider = scripted(requests, reply, OpenAiCompatibleProvider.replyOf(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}"));
        List<ToolCall> executed = new ArrayList<>();
        ToolLoop loop = new ToolLoop(provider, TOOLS, calls -> {
            executed.addAll(calls);
            return CompletableFuture.completedFuture(calls.stream().map(c -> ToolResult.ok(c.id(), "ok")).toList());
        }, 8);

        String answer = loop.run(new OutgoingRequest("h", OutgoingRequest.Route.CHAT, List.of(),
                List.of(new OutgoingRequest.Section("question", "q")), TOOLS, List.of())).join();

        assertEquals("done", answer);
        assertEquals(List.of("call_a"), executed.stream().map(ToolCall::id).toList());
        AgentMessage.ToolResults results = (AgentMessage.ToolResults) requests.get(1).get(2);
        assertTrue(results.results().get(1).isError());
        assertEquals(ToolLoop.BAD_ARGUMENTS, results.results().get(1).content());
    }

    @Test
    void missingCallIdsAreFilledInAndArgumentObjectsAreAccepted() {
        AgentReply reply = OpenAiCompatibleProvider.replyOf("""
                {"choices":[{"message":{"role":"assistant","content":"","tool_calls":[
                  {"function":{"name":"get_inventory","arguments":{}}}]}}]}""");

        ToolCall call = reply.message().calls().get(0);
        assertEquals("call_0", call.id());
        assertEquals(new JsonObject(), call.input());
    }

    @Test
    void cutOffAndRefusedAnswersAreErrors() {
        AiException cut = assertThrows(AiException.class, () -> OpenAiCompatibleProvider.replyOf(
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"Half\"}}]}"));
        AiException refused = assertThrows(AiException.class, () -> OpenAiCompatibleProvider.textOf(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,\"refusal\":\"No.\"}}]}"));
        AiException empty = assertThrows(AiException.class, () -> OpenAiCompatibleProvider.textOf("{\"choices\":[]}"));

        assertEquals(AiException.Kind.BAD_RESPONSE, cut.kind());
        assertEquals(AiException.Kind.REFUSED, refused.kind());
        assertEquals(AiException.Kind.BAD_RESPONSE, empty.kind());
    }

    private static String role(JsonArray messages, int index) {
        return messages.get(index).getAsJsonObject().get("role").getAsString();
    }

    /** Replays converse replies and records each request's messages. */
    private static AiProvider scripted(List<List<AgentMessage>> requests, AgentReply... replies) {
        Deque<AgentReply> queue = new ArrayDeque<>(List.of(replies));
        return new AiProvider() {
            @Override
            public CompletableFuture<AgentReply> converse(List<AgentMessage> messages, List<ToolSpec> tools, boolean enabled) {
                requests.add(List.copyOf(messages));
                return CompletableFuture.completedFuture(queue.removeFirst());
            }

            @Override
            public CompletableFuture<ItemPick> pickItem(OutgoingRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<String> answer(OutgoingRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }
}

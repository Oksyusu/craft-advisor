package com.kuh.craftadvisor.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class ClaudeToolFormatTest {
    private static final ClaudeProvider PROVIDER = new ClaudeProvider("key", "claude-opus-5-5", 2000);
    private static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec("search_items", "search", JsonParser.parseString("{\"type\":\"object\"}").getAsJsonObject()),
            new ToolSpec("get_recipes", "recipes", JsonParser.parseString("{\"type\":\"object\"}").getAsJsonObject()));
    private static final String TOOL_USE_RESPONSE = """
            {"stop_reason":"tool_use","content":[
              {"type":"thinking","thinking":"","signature":"sig"},
              {"type":"text","text":"Looking it up."},
              {"type":"tool_use","id":"toolu_1","name":"get_recipes","input":{"uid":"create:andesite_alloy"}}]}""";

    @Test
    void toolUseResponseBecomesCallsAndKeepsTheWholeContent() {
        AgentReply reply = ClaudeProvider.replyOf(TOOL_USE_RESPONSE);

        assertTrue(reply.wantsTools());
        ToolCall call = reply.message().calls().get(0);
        assertEquals("toolu_1", call.id());
        assertEquals("get_recipes", call.name());
        assertEquals("create:andesite_alloy", call.input().get("uid").getAsString());
        assertEquals(3, reply.message().providerRaw().getAsJsonArray().size());
    }

    @Test
    void nextRequestEchoesTheAssistantBlocksAndSendsToolResults() {
        AgentReply reply = ClaudeProvider.replyOf(TOOL_USE_RESPONSE);
        List<AgentMessage> messages = List.of(new AgentMessage.User("q"), reply.message(),
                new AgentMessage.ToolResults(List.of(ToolResult.error("toolu_1", "Unknown uid"))));

        JsonObject body = PROVIDER.converseBody(messages, TOOLS, false);

        JsonArray sent = body.getAsJsonArray("messages");
        assertEquals(reply.message().providerRaw(), sent.get(1).getAsJsonObject().get("content"));
        JsonObject result = sent.get(2).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("tool_result", result.get("type").getAsString());
        assertEquals("toolu_1", result.get("tool_use_id").getAsString());
        assertTrue(result.get("is_error").getAsBoolean());
        assertEquals("none", body.getAsJsonObject("tool_choice").get("type").getAsString());
    }

    @Test
    void toolsKeepTheirOrderAndTheLastOneCarriesTheCacheBreakpoint() {
        JsonObject body = PROVIDER.converseBody(List.of(new AgentMessage.User("q")), TOOLS, true);

        JsonArray tools = body.getAsJsonArray("tools");
        assertEquals("search_items", tools.get(0).getAsJsonObject().get("name").getAsString());
        assertFalse(tools.get(0).getAsJsonObject().has("cache_control"));
        assertTrue(tools.get(1).getAsJsonObject().has("cache_control"));
        assertEquals("auto", body.getAsJsonObject("tool_choice").get("type").getAsString());
        assertEquals("medium", body.getAsJsonObject("output_config").get("effort").getAsString());
    }

    @Test
    void answerCutOffAtMaxTokensIsABadResponse() {
        AiException error = assertThrows(AiException.class, () -> ClaudeProvider.replyOf(
                "{\"stop_reason\":\"max_tokens\",\"content\":[{\"type\":\"text\",\"text\":\"Half\"}]}"));

        assertEquals(AiException.Kind.BAD_RESPONSE, error.kind());
    }

    @Test
    void finalAnswerDoesNotWantTools() {
        AgentReply reply = ClaudeProvider.replyOf(
                "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"Mix it.\"}]}");

        assertFalse(reply.wantsTools());
        assertEquals("Mix it.", reply.message().text());
    }
}

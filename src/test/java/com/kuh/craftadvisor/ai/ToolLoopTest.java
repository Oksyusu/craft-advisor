package com.kuh.craftadvisor.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class ToolLoopTest {
    private static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec("search_items", "search", new JsonObject()),
            new ToolSpec("get_recipes", "recipes", new JsonObject()));
    private static final OutgoingRequest QUESTION = new OutgoingRequest("host", OutgoingRequest.Route.CHAT, List.of(),
            List.of(new OutgoingRequest.Section("question", "how do I make andesite alloy?")), TOOLS, List.of());

    /** Replays scripted replies and records what each request carried. */
    private static final class FakeProvider implements AiProvider {
        private final Deque<AgentReply> replies;
        final List<List<AgentMessage>> requests = new ArrayList<>();
        final List<Boolean> toolsEnabled = new ArrayList<>();

        FakeProvider(AgentReply... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public CompletableFuture<AgentReply> converse(List<AgentMessage> messages, List<ToolSpec> tools, boolean enabled) {
            requests.add(List.copyOf(messages));
            toolsEnabled.add(enabled);
            return CompletableFuture.completedFuture(replies.removeFirst());
        }

        @Override
        public CompletableFuture<ItemPick> pickItem(OutgoingRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<String> answer(OutgoingRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    /** Runs calls directly and records them; the result names the call. */
    private static final class DirectExecutor implements ToolLoop.Executor {
        final List<ToolCall> executed = new ArrayList<>();

        @Override
        public CompletableFuture<List<ToolResult>> execute(List<ToolCall> calls) {
            executed.addAll(calls);
            return CompletableFuture.completedFuture(
                    calls.stream().map(c -> ToolResult.ok(c.id(), "result of " + c.name())).toList());
        }
    }

    private static ToolCall call(String id, String name) {
        return new ToolCall(id, name, new JsonObject());
    }

    private static AgentReply wantsTools(ToolCall... calls) {
        return new AgentReply(new AgentMessage.Assistant("", List.of(calls), new JsonObject()), true);
    }

    private static AgentReply answer(String text) {
        return new AgentReply(new AgentMessage.Assistant(text, List.of(), new JsonObject()), false);
    }

    private static AgentMessage.ToolResults lastResults(List<AgentMessage> request) {
        return assertInstanceOf(AgentMessage.ToolResults.class, request.get(request.size() - 1));
    }

    @Test
    void toolCallResultIsSentBackAndTheFinalAnswerIsReturned() {
        FakeProvider provider = new FakeProvider(wantsTools(call("t1", "get_recipes")), answer("Mix andesite and iron."));
        DirectExecutor executor = new DirectExecutor();

        String answer = new ToolLoop(provider, TOOLS, executor, 8).run(QUESTION).join();

        assertEquals("Mix andesite and iron.", answer);
        assertEquals(List.of("get_recipes"), executor.executed.stream().map(ToolCall::name).toList());
        List<AgentMessage> second = provider.requests.get(1);
        assertEquals(new AgentMessage.User("how do I make andesite alloy?"), second.get(0));
        assertInstanceOf(AgentMessage.Assistant.class, second.get(1));
        assertEquals(List.of(ToolResult.ok("t1", "result of get_recipes")), lastResults(second).results());
    }

    @Test
    void severalCallsInOneReplyRunInOrderAndReturnInOneMessage() {
        FakeProvider provider = new FakeProvider(
                wantsTools(call("a", "search_items"), call("b", "get_recipes"), call("c", "search_items")),
                answer("done"));
        DirectExecutor executor = new DirectExecutor();

        new ToolLoop(provider, TOOLS, executor, 8).run(QUESTION).join();

        assertEquals(List.of("a", "b", "c"), executor.executed.stream().map(ToolCall::id).toList());
        assertEquals(List.of("a", "b", "c"),
                lastResults(provider.requests.get(1)).results().stream().map(ToolResult::callId).toList());
        assertEquals(2, provider.requests.size());
    }

    @Test
    void reachingTheLimitTurnsToolsOffForTheLastRequest() {
        FakeProvider provider = new FakeProvider(
                wantsTools(call("a", "search_items")),
                wantsTools(call("b", "search_items"), call("c", "get_recipes")),
                answer("best answer so far"));
        DirectExecutor executor = new DirectExecutor();

        String answer = new ToolLoop(provider, TOOLS, executor, 2).run(QUESTION).join();

        assertEquals("best answer so far", answer);
        assertEquals(List.of(true, true, false), provider.toolsEnabled);
        assertEquals(List.of("a", "b"), executor.executed.stream().map(ToolCall::id).toList());
        ToolResult overLimit = lastResults(provider.requests.get(2)).results().get(1);
        assertEquals("c", overLimit.callId());
        assertTrue(overLimit.isError());
    }

    @Test
    void unknownToolNameGetsAnErrorResultAndTheLoopContinues() {
        FakeProvider provider = new FakeProvider(
                wantsTools(call("x", "open_recipe"), call("y", "search_items")), answer("ok"));
        DirectExecutor executor = new DirectExecutor();

        String answer = new ToolLoop(provider, TOOLS, executor, 8).run(QUESTION).join();

        assertEquals("ok", answer);
        assertEquals(List.of("y"), executor.executed.stream().map(ToolCall::id).toList());
        List<ToolResult> results = lastResults(provider.requests.get(1)).results();
        assertEquals("x", results.get(0).callId());
        assertTrue(results.get(0).isError());
        assertTrue(results.get(0).content().contains("open_recipe"));
        assertFalse(results.get(1).isError());
    }

    @Test
    void historyTurnsComeBeforeTheQuestion() {
        OutgoingRequest withHistory = new OutgoingRequest("host", OutgoingRequest.Route.CHAT,
                List.of(new AiProvider.Turn(true, "earlier"), new AiProvider.Turn(false, "reply")),
                QUESTION.sections(), TOOLS, List.of());

        List<AgentMessage> messages = ToolLoop.firstMessages(withHistory);

        assertEquals(List.of(new AgentMessage.User("earlier"), new AgentMessage.Assistant("reply", List.of(), null),
                new AgentMessage.User("how do I make andesite alloy?")), messages);
    }
}

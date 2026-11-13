package com.kuh.craftadvisor.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Runs a free question as a tool-calling conversation: ask, run the requested tools, send the results, repeat
 * until the AI answers. Provider-neutral and thread-neutral: nothing here waits; each step is a composed future.
 * After {@code maxToolCalls} calls the next request turns tools off, so the AI answers with what it has.
 */
public final class ToolLoop {
    static final String LIMIT_REACHED = "Tool call limit reached; answer with the results you already have.";
    static final String BAD_ARGUMENTS = "The arguments were not a valid JSON object; call the tool again with a JSON object.";

    /** Runs tool calls in order and returns one result per call (on whatever thread the data lives on). */
    @FunctionalInterface
    public interface Executor {
        CompletableFuture<List<ToolResult>> execute(List<ToolCall> calls);
    }

    private final AiProvider provider;
    private final List<ToolSpec> tools;
    private final Set<String> toolNames;
    private final Executor executor;
    private final int maxToolCalls;

    public ToolLoop(AiProvider provider, List<ToolSpec> tools, Executor executor, int maxToolCalls) {
        this.provider = provider;
        this.tools = List.copyOf(tools);
        this.toolNames = this.tools.stream().map(ToolSpec::name).collect(Collectors.toUnmodifiableSet());
        this.executor = executor;
        this.maxToolCalls = maxToolCalls;
    }

    /** The first request's messages: the shared history, then the question and its notes. */
    public static List<AgentMessage> firstMessages(OutgoingRequest request) {
        List<AgentMessage> messages = new ArrayList<>();
        for (AiProvider.Turn turn : request.history()) {
            messages.add(turn.fromPlayer()
                    ? new AgentMessage.User(turn.text())
                    : new AgentMessage.Assistant(turn.text(), List.of(), null));
        }
        messages.add(new AgentMessage.User(request.userText()));
        return messages;
    }

    /** The final answer text. */
    public CompletableFuture<String> run(OutgoingRequest request) {
        return step(firstMessages(request), 0);
    }

    private CompletableFuture<String> step(List<AgentMessage> messages, int used) {
        boolean toolsEnabled = used < maxToolCalls;
        return provider.converse(messages, tools, toolsEnabled).thenCompose(reply -> {
            if (!reply.wantsTools() || !toolsEnabled) {
                return CompletableFuture.completedFuture(finalText(reply));
            }
            List<ToolCall> calls = reply.message().calls();
            int allowed = Math.min(calls.size(), maxToolCalls - used);
            List<ToolCall> known = calls.subList(0, allowed).stream().filter(this::runnable).toList();
            return executor.execute(known).thenCompose(results -> {
                List<AgentMessage> next = new ArrayList<>(messages);
                next.add(reply.message());
                next.add(new AgentMessage.ToolResults(inCallOrder(calls, allowed, results)));
                return step(next, used + allowed);
            });
        });
    }

    private boolean runnable(ToolCall call) {
        return toolNames.contains(call.name()) && call.input() != null;
    }

    /** One result per call, in the order the AI made them; unknown tools, bad arguments and calls over the limit get errors. */
    private List<ToolResult> inCallOrder(List<ToolCall> calls, int allowed, List<ToolResult> executed) {
        List<ToolResult> results = new ArrayList<>();
        int next = 0;
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            if (i >= allowed) {
                results.add(ToolResult.error(call.id(), LIMIT_REACHED));
            } else if (!toolNames.contains(call.name())) {
                results.add(ToolResult.error(call.id(), "Unknown tool: " + call.name() + ". Available: " + toolNames));
            } else if (call.input() == null) {
                results.add(ToolResult.error(call.id(), BAD_ARGUMENTS));
            } else {
                results.add(executed.get(next++));
            }
        }
        return results;
    }

    private static String finalText(AgentReply reply) {
        String text = reply.message().text();
        if (text == null || text.isBlank()) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, "no text in response");
        }
        return text;
    }
}

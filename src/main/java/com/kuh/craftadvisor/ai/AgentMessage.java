package com.kuh.craftadvisor.ai;

import java.util.List;

import com.google.gson.JsonElement;

/** One message of a tool-calling conversation, in a provider-neutral form. */
public sealed interface AgentMessage {

    record User(String text) implements AgentMessage {
    }

    /**
     * An AI turn. {@code providerRaw} is the provider's own reply (for Claude, the whole content array with
     * thinking blocks) and is sent back unchanged; it is null for turns that came from the stored history.
     */
    record Assistant(String text, List<ToolCall> calls, JsonElement providerRaw) implements AgentMessage {
        public Assistant {
            calls = List.copyOf(calls);
        }
    }

    record ToolResults(List<ToolResult> results) implements AgentMessage {
        public ToolResults {
            results = List.copyOf(results);
        }
    }
}

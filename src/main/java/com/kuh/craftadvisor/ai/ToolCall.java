package com.kuh.craftadvisor.ai;

import com.google.gson.JsonObject;

/**
 * One tool call the AI asked for; {@code id} pairs it with its {@link ToolResult}. {@code input} is null when the
 * AI sent arguments that are not a JSON object; the call is then answered with an error, not run.
 */
public record ToolCall(String id, String name, JsonObject input) {
}

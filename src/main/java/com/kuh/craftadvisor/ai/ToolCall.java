package com.kuh.craftadvisor.ai;

import com.google.gson.JsonObject;

/** One tool call the AI asked for; {@code id} pairs it with its {@link ToolResult}. */
public record ToolCall(String id, String name, JsonObject input) {
}

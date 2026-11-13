package com.kuh.craftadvisor.ai;

import com.google.gson.JsonObject;

/** A tool the AI may call; {@code inputSchema} is a JSON Schema object. */
public record ToolSpec(String name, String description, JsonObject inputSchema) {
}

package com.kuh.craftadvisor.ai;

/** What a tool returned (compact JSON or an error message) for the call with id {@code callId}. */
public record ToolResult(String callId, String content, boolean isError) {
    public static ToolResult ok(String callId, String content) {
        return new ToolResult(callId, content, false);
    }

    public static ToolResult error(String callId, String message) {
        return new ToolResult(callId, message, true);
    }
}

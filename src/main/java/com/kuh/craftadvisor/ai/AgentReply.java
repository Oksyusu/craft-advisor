package com.kuh.craftadvisor.ai;

/** A {@link AiProvider#converse} reply: either a final answer or tool calls to run ({@code wantsTools}). */
public record AgentReply(AgentMessage.Assistant message, boolean wantsTools) {
}

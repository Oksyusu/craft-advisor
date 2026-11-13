package com.kuh.craftadvisor.ai;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * An AI backend. Calls never block the caller; futures complete on a background thread and fail
 * with {@link AiException}. Only Claude is implemented; other providers can be added behind this.
 */
public interface AiProvider {

    /** Decide whether the request names an item to craft, choosing only from the candidates ({@link OutgoingRequest.Route#PICK}). */
    CompletableFuture<ItemPick> pickItem(OutgoingRequest request);

    /** Advice on a plan computed by code, which must not invent numbers ({@link OutgoingRequest.Route#ADVICE}). */
    CompletableFuture<String> answer(OutgoingRequest request);

    /**
     * One step of a free question ({@link OutgoingRequest.Route#CHAT}): the AI answers or asks for tool calls.
     * With {@code toolsEnabled} false the same tools are still listed but the AI must answer.
     */
    CompletableFuture<AgentReply> converse(List<AgentMessage> messages, List<ToolSpec> tools, boolean toolsEnabled);

    record Turn(boolean fromPlayer, String text) {
    }
}

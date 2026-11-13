package com.kuh.craftadvisor.ai;

import java.util.concurrent.CompletableFuture;

/**
 * An AI backend. Calls never block the caller; futures complete on a background thread and fail
 * with {@link AiException}. Only Claude is implemented; other providers can be added behind this.
 */
public interface AiProvider {

    /** Decide whether the request names an item to craft, choosing only from the candidates ({@link OutgoingRequest.Route#PICK}). */
    CompletableFuture<ItemPick> pickItem(OutgoingRequest request);

    /** Advice on a plan computed by code, which must not invent numbers, or a free question with context. */
    CompletableFuture<String> answer(OutgoingRequest request);

    record Turn(boolean fromPlayer, String text) {
    }
}

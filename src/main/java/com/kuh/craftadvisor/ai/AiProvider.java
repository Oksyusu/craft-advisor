package com.kuh.craftadvisor.ai;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * An AI backend. Calls never block the caller; futures complete on a background thread and fail
 * with {@link AiException}. Only Claude is implemented; other providers can be added behind this.
 */
public interface AiProvider {

    /** Decide whether the request names an item to craft, choosing only from {@code candidateLines}. */
    CompletableFuture<ItemPick> pickItem(String request, List<String> candidateLines);

    /** Short advice for a plan computed by code ({@code planJson}); must not invent numbers. */
    CompletableFuture<String> advise(String request, String planJson);

    /** Free question with recent conversation and an inventory summary as context. */
    CompletableFuture<String> chat(List<Turn> history, String question, String inventorySummary);

    record Turn(boolean fromPlayer, String text) {
    }
}

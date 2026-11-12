package com.kuh.craftadvisor.ai;

/** Structured answer of the item-picking call. {@code itemId} is empty when intent is "question". */
public record ItemPick(String intent, String itemId, String reason) {
    public boolean wantsCraft() {
        return "craft".equals(intent) && itemId != null && !itemId.isBlank();
    }
}

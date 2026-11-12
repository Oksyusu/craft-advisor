package com.kuh.craftadvisor.planner;

import java.util.List;

/**
 * One input slot of a recipe. Any of the alternatives satisfies it (tags such as "any planks").
 * The first alternative is the one JEI displays first and the one expanded when none is owned.
 */
public record IngredientSlot(List<ItemKey> alternatives, int count) {
    public IngredientSlot {
        alternatives = List.copyOf(alternatives);
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("slot needs at least one alternative");
        }
        if (count < 1) {
            throw new IllegalArgumentException("slot count must be positive");
        }
    }

    public static IngredientSlot of(String id, int count) {
        return new IngredientSlot(List.of(ItemKey.of(id)), count);
    }

    public ItemKey primary() {
        return alternatives.get(0);
    }
}

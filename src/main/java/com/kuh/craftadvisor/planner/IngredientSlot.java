package com.kuh.craftadvisor.planner;

import java.util.List;

/**
 * One input slot of a recipe. Any of the alternatives satisfies it (tags such as "any planks").
 * The first alternative is the one JEI displays first; {@link CraftPlanner} decides which one to expand.
 * {@code count} is in the ingredient's own unit (mB for fluids).
 */
public record IngredientSlot(List<IngredientKey> alternatives, int count) {
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
        return new IngredientSlot(List.of(IngredientKey.item(id)), count);
    }
}

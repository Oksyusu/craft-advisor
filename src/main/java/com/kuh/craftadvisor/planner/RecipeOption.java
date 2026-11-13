package com.kuh.craftadvisor.planner;

import java.util.List;

/**
 * A recipe as the planner sees it: input slots, how many of the target one craft yields,
 * the machines (JEI catalysts) needed to run it, and its JEI recipe category uid (e.g. {@code create:mixing}).
 */
public record RecipeOption(List<IngredientSlot> inputs, int outputCount, List<IngredientKey> machines, String category) {
    public RecipeOption {
        inputs = List.copyOf(inputs);
        machines = List.copyOf(machines);
        if (outputCount < 1) {
            throw new IllegalArgumentException("output count must be positive");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("recipe category must not be blank");
        }
    }

    public boolean uses(IngredientKey item) {
        return inputs.stream().anyMatch(slot -> slot.alternatives().contains(item));
    }
}

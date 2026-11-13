package com.kuh.craftadvisor.planner;

import java.util.List;

/**
 * A recipe as the planner sees it: input slots, how many of the target one craft yields,
 * the machines (JEI catalysts) needed to run it, and its JEI recipe category uid (e.g. {@code create:mixing}).
 * {@code outputs} lists every output slot for display (may be empty); the planner only uses {@code outputCount}.
 */
public record RecipeOption(List<IngredientSlot> inputs, int outputCount, List<IngredientKey> machines, String category,
                           List<IngredientSlot> outputs) {
    public RecipeOption {
        inputs = List.copyOf(inputs);
        machines = List.copyOf(machines);
        outputs = List.copyOf(outputs);
        if (outputCount < 1) {
            throw new IllegalArgumentException("output count must be positive");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("recipe category must not be blank");
        }
    }

    public RecipeOption(List<IngredientSlot> inputs, int outputCount, List<IngredientKey> machines, String category) {
        this(inputs, outputCount, machines, category, List.of());
    }

    public boolean uses(IngredientKey item) {
        return inputs.stream().anyMatch(slot -> slot.alternatives().contains(item));
    }
}

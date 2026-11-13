package com.kuh.craftadvisor.planner;

import java.util.List;

/**
 * A recipe as the planner sees it: item input slots, how many of the target one craft yields,
 * and the machines (JEI catalysts) needed to run it.
 */
public record RecipeOption(List<IngredientSlot> inputs, int outputCount, List<IngredientKey> machines) {
    public RecipeOption {
        inputs = List.copyOf(inputs);
        machines = List.copyOf(machines);
        if (outputCount < 1) {
            throw new IllegalArgumentException("output count must be positive");
        }
    }

    public boolean uses(IngredientKey item) {
        return inputs.stream().anyMatch(slot -> slot.alternatives().contains(item));
    }
}

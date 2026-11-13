package com.kuh.craftadvisor.planner;

/** One target of a multi-goal plan: craft {@code amount} of {@code item}. */
public record Goal(IngredientKey item, int amount) {
    public Goal {
        if (item == null) {
            throw new IllegalArgumentException("goal item must not be null");
        }
        if (amount < 1) {
            throw new IllegalArgumentException("goal amount must be positive");
        }
    }
}

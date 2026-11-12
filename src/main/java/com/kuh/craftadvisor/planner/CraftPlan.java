package com.kuh.craftadvisor.planner;

import java.util.List;

/** Result of planning: what to craft, which raw materials are still missing, and which machines are needed. */
public record CraftPlan(ItemKey target, int amount, List<Step> steps, List<Missing> missing, List<ItemKey> machines) {
    public CraftPlan {
        steps = List.copyOf(steps);
        missing = List.copyOf(missing);
        machines = List.copyOf(machines);
    }

    /** Craft {@code crafts} times with {@code recipe} to get {@code item}; depth 0 is the target. */
    public record Step(ItemKey item, int crafts, int depth, RecipeOption recipe) {
    }

    /** A material the player still has to gather, and why the planner did not expand it further. */
    public record Missing(ItemKey item, int count, StopReason reason) {
    }

    public enum StopReason {
        /** Nothing in JEI produces it (ores, logs, mob drops, ...). */
        NO_RECIPE,
        /** Every recipe for it would loop back into an item already being crafted. */
        CYCLE,
        /** The tree reached the maximum depth. */
        MAX_DEPTH
    }
}

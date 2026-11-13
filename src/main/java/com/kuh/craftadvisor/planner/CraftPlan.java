package com.kuh.craftadvisor.planner;

import java.util.List;

/**
 * Result of planning: what to craft, which raw materials are still missing, which machines are needed,
 * and which alternatives the planner picked where it had a choice.
 */
public record CraftPlan(IngredientKey target, int amount, List<Step> steps, List<Missing> missing,
                        List<IngredientKey> machines, List<Choice> choices) {
    public CraftPlan {
        steps = List.copyOf(steps);
        missing = List.copyOf(missing);
        machines = List.copyOf(machines);
        choices = List.copyOf(choices);
    }

    /** Craft {@code crafts} times with {@code recipe} to get {@code item}; depth 0 is the target. */
    public record Step(IngredientKey item, int crafts, int depth, RecipeOption recipe) {
    }

    /**
     * A material the player still has to gather, and why the planner did not expand it further.
     * {@code count} is in the ingredient's own unit (mB for fluids).
     */
    public record Missing(IngredientKey item, int count, StopReason reason) {
    }

    public enum StopReason {
        /** Nothing in JEI produces it (ores, logs, mob drops, ...). */
        NO_RECIPE,
        /** Every recipe for it would loop back into an item already being crafted. */
        CYCLE,
        /** The tree reached the maximum depth. */
        MAX_DEPTH,
        /** Not an item (fluid, ...): never expanded and never taken from the inventory. */
        NOT_ITEM
    }

    /**
     * A choice the planner made. For a slot with several alternatives, {@code slotOwner} is the item whose
     * recipe has the slot, {@code chosen} the alternative that was expanded, and {@code recipeCategory} is empty.
     * For a {@code recipe} rule, {@code slotOwner} and {@code chosen} are the crafted item,
     * {@code alternativeCount} is how many recipes were usable, and {@code recipeCategory} is the rule's category.
     */
    public record Choice(IngredientKey slotOwner, IngredientKey chosen, int alternativeCount, ChoiceReason reason,
                         String recipeCategory) {
        public boolean isRecipe() {
            return !recipeCategory.isEmpty();
        }
    }

    public enum ChoiceReason {
        /** A preference rule picked it. */
        RULE,
        /** The inventory already had some of it. */
        OWNED,
        /** No rule and nothing owned: the first alternative (JEI order). */
        DEFAULT,
        /** A recipe rule exists but no usable recipe of that category was found; the default choice was used. */
        RULE_UNUSABLE
    }
}

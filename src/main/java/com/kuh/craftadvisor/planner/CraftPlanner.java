package com.kuh.craftadvisor.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Expands a target item into a crafting tree using deterministic rules, so the numbers never
 * come from the AI:
 * <ul>
 *   <li>Recipe choice: the recipe with the most input slots the inventory already covers; ties keep JEI order.
 *       Recipes that use an item on the current path are skipped (e.g. iron block back into iron ingots).</li>
 *   <li>A slot is satisfied by any of its alternatives; otherwise its first alternative is expanded.</li>
 *   <li>Expansion stops at items with no recipe, items that only loop back, and at {@link #MAX_DEPTH}.</li>
 *   <li>Crafts are rounded up by the recipe's output count; leftovers are reused by later slots.</li>
 * </ul>
 * The caller's inventory map is never modified.
 */
public final class CraftPlanner {
    public static final int MAX_DEPTH = 8;

    private final RecipeSource recipes;

    public CraftPlanner(RecipeSource recipes) {
        this.recipes = recipes;
    }

    public CraftPlan plan(ItemKey target, int amount, Map<ItemKey, Integer> inventory) {
        Run run = new Run(new HashMap<>(inventory));
        run.craft(target, amount, 0, List.of());
        List<CraftPlan.Missing> missing = new ArrayList<>();
        run.missing.forEach((item, count) -> missing.add(new CraftPlan.Missing(item, count, run.reasons.get(item))));
        return new CraftPlan(target, amount, run.steps, missing, new ArrayList<>(run.machines));
    }

    /** Mutable working state of one planning call. */
    private final class Run {
        private final Map<ItemKey, Integer> pool;
        private final List<CraftPlan.Step> steps = new ArrayList<>();
        private final Map<ItemKey, Integer> missing = new LinkedHashMap<>();
        private final Map<ItemKey, CraftPlan.StopReason> reasons = new HashMap<>();
        private final Set<ItemKey> machines = new LinkedHashSet<>();

        private Run(Map<ItemKey, Integer> pool) {
            this.pool = pool;
        }

        /** The target (depth 0) is always crafted, even if the player already owns some. */
        private void craft(ItemKey item, int needed, int depth, List<ItemKey> path) {
            if (depth >= MAX_DEPTH) {
                addMissing(item, needed, CraftPlan.StopReason.MAX_DEPTH);
                return;
            }
            List<ItemKey> nextPath = append(path, item);
            List<RecipeOption> usable = recipes.recipesFor(item).stream()
                    .filter(recipe -> nextPath.stream().noneMatch(recipe::uses))
                    .toList();
            if (usable.isEmpty()) {
                boolean hasAny = !recipes.recipesFor(item).isEmpty();
                addMissing(item, needed, hasAny ? CraftPlan.StopReason.CYCLE : CraftPlan.StopReason.NO_RECIPE);
                return;
            }
            RecipeOption recipe = choose(usable);
            int crafts = ceilDiv(needed, recipe.outputCount());
            steps.add(new CraftPlan.Step(item, crafts, depth, recipe));
            machines.addAll(recipe.machines());
            int leftover = crafts * recipe.outputCount() - needed;
            if (leftover > 0) {
                pool.merge(item, leftover, Integer::sum);
            }
            for (IngredientSlot slot : recipe.inputs()) {
                int remaining = takeFromPool(slot, slot.count() * crafts);
                if (remaining > 0) {
                    obtain(slot.primary(), remaining, depth + 1, nextPath);
                }
            }
        }

        /** Ingredients below the target: use what the pool still has, craft the rest. */
        private void obtain(ItemKey item, int needed, int depth, List<ItemKey> path) {
            int remaining = take(item, needed);
            if (remaining > 0) {
                craft(item, remaining, depth, path);
            }
        }

        private RecipeOption choose(List<RecipeOption> options) {
            RecipeOption best = options.get(0);
            int bestScore = coveredSlots(best);
            for (RecipeOption option : options.subList(1, options.size())) {
                int score = coveredSlots(option);
                if (score > bestScore) {
                    best = option;
                    bestScore = score;
                }
            }
            return best;
        }

        private int coveredSlots(RecipeOption recipe) {
            int covered = 0;
            for (IngredientSlot slot : recipe.inputs()) {
                int owned = slot.alternatives().stream().mapToInt(alt -> pool.getOrDefault(alt, 0)).sum();
                if (owned >= slot.count()) {
                    covered++;
                }
            }
            return covered;
        }

        private int takeFromPool(IngredientSlot slot, int needed) {
            int remaining = needed;
            for (ItemKey alt : slot.alternatives()) {
                remaining = take(alt, remaining);
                if (remaining == 0) {
                    break;
                }
            }
            return remaining;
        }

        private int take(ItemKey item, int needed) {
            int owned = pool.getOrDefault(item, 0);
            int used = Math.min(owned, needed);
            if (used > 0) {
                pool.put(item, owned - used);
            }
            return needed - used;
        }

        private void addMissing(ItemKey item, int count, CraftPlan.StopReason reason) {
            missing.merge(item, count, Integer::sum);
            reasons.putIfAbsent(item, reason);
        }
    }

    private static List<ItemKey> append(List<ItemKey> path, ItemKey item) {
        List<ItemKey> next = new ArrayList<>(path);
        next.add(item);
        return List.copyOf(next);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}

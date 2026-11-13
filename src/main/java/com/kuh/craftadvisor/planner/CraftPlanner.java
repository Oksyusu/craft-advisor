package com.kuh.craftadvisor.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Expands a target item into a crafting tree using deterministic rules, so the numbers never
 * come from the AI:
 * <ul>
 *   <li>Recipe choice: a {@code recipe} rule's category if such a recipe is usable; then the recipe with the most
 *       input slots the inventory already covers; ties keep JEI order.
 *       Recipes that use an item on the current path are skipped (e.g. iron block back into iron ingots).</li>
 *   <li>A slot is satisfied by any of its alternatives. What is still short is expanded from an alternative the
 *       inventory has some of, else the first {@code prefer} rule that lists one, else the first alternative.</li>
 *   <li>Expansion stops at items with no recipe, items that only loop back, and at {@link #MAX_DEPTH}.
 *       Non-item ingredients (fluids, ...) are never expanded nor taken from the inventory.</li>
 *   <li>Crafts are rounded up by the recipe's output count; leftovers are reused by later slots.</li>
 * </ul>
 * The caller's inventory map is never modified.
 */
public final class CraftPlanner {
    public static final int MAX_DEPTH = 8;

    private final RecipeSource recipes;
    private final RecipePreferences preferences;

    public CraftPlanner(RecipeSource recipes) {
        this(recipes, RecipePreferences.NONE);
    }

    public CraftPlanner(RecipeSource recipes, RecipePreferences preferences) {
        this.recipes = recipes;
        this.preferences = preferences;
    }

    public CraftPlan plan(IngredientKey target, int amount, Map<IngredientKey, Integer> inventory) {
        MultiPlan all = planAll(List.of(new Goal(target, amount)), inventory);
        return new CraftPlan(target, amount, all.steps(), all.missing(), all.machines(), all.choices());
    }

    /**
     * Plans the goals in order against one shared copy of the inventory: earlier goals use the stock (and the
     * leftovers of their crafts) first. Each goal's own item is always crafted, as in {@link #plan}.
     * Missing materials are merged. A step for the same item and recipe as one of an earlier goal is added
     * into that step; steps within one goal stay as {@link #plan} lists them.
     */
    public MultiPlan planAll(List<Goal> goals, Map<IngredientKey, Integer> inventory) {
        Map<IngredientKey, Integer> pool = new HashMap<>();
        inventory.forEach((key, count) -> {
            if (key.isItem()) {
                pool.put(key, count); // non-items are never taken from the inventory
            }
        });
        Run run = new Run(pool);
        List<List<CraftPlan.Step>> stepsByGoal = new ArrayList<>();
        for (int i = 0; i < goals.size(); i++) {
            int firstStep = run.steps.size();
            run.goal = i;
            run.craft(goals.get(i).item(), goals.get(i).amount(), 0, List.of());
            stepsByGoal.add(List.copyOf(run.steps.subList(firstStep, run.steps.size())));
        }
        List<CraftPlan.Missing> missing = new ArrayList<>();
        run.missing.forEach((item, count) -> missing.add(new CraftPlan.Missing(item, count, run.reasons.get(item))));
        return new MultiPlan(goals, mergeAcrossGoals(stepsByGoal), missing, new ArrayList<>(run.machines),
                run.missingByGoal, new ArrayList<>(run.choices));
    }

    private static List<CraftPlan.Step> mergeAcrossGoals(List<List<CraftPlan.Step>> stepsByGoal) {
        List<CraftPlan.Step> merged = new ArrayList<>();
        Map<List<Object>, Integer> fromEarlierGoals = new HashMap<>();
        for (List<CraftPlan.Step> steps : stepsByGoal) {
            Map<List<Object>, Integer> fromThisGoal = new HashMap<>();
            for (CraftPlan.Step step : steps) {
                List<Object> key = List.of(step.item(), step.recipe());
                Integer earlier = fromEarlierGoals.get(key);
                if (earlier == null) {
                    fromThisGoal.putIfAbsent(key, merged.size());
                    merged.add(step);
                } else {
                    CraftPlan.Step into = merged.get(earlier);
                    merged.set(earlier, new CraftPlan.Step(into.item(), into.crafts() + step.crafts(), into.depth(),
                            into.recipe()));
                }
            }
            fromEarlierGoals.putAll(fromThisGoal);
        }
        return merged;
    }

    /** Mutable working state of one planning call. */
    private final class Run {
        private final Map<IngredientKey, Integer> pool;
        private final List<CraftPlan.Step> steps = new ArrayList<>();
        private final Map<IngredientKey, Integer> missing = new LinkedHashMap<>();
        private final Map<IngredientKey, CraftPlan.StopReason> reasons = new HashMap<>();
        private final Map<IngredientKey, Map<Integer, Integer>> missingByGoal = new LinkedHashMap<>();
        private final Set<IngredientKey> machines = new LinkedHashSet<>();
        private final Set<CraftPlan.Choice> choices = new LinkedHashSet<>();
        /** Index of the goal being planned; missing materials are credited to it. */
        private int goal;

        private Run(Map<IngredientKey, Integer> pool) {
            this.pool = pool;
        }

        /** The target (depth 0) is always crafted, even if the player already owns some. */
        private void craft(IngredientKey item, int needed, int depth, List<IngredientKey> path) {
            if (depth >= MAX_DEPTH) {
                addMissing(item, needed, CraftPlan.StopReason.MAX_DEPTH);
                return;
            }
            List<IngredientKey> nextPath = append(path, item);
            List<RecipeOption> usable = recipes.recipesFor(item).stream()
                    .filter(recipe -> nextPath.stream().noneMatch(recipe::uses))
                    .toList();
            if (usable.isEmpty()) {
                boolean hasAny = !recipes.recipesFor(item).isEmpty();
                addMissing(item, needed, hasAny ? CraftPlan.StopReason.CYCLE : CraftPlan.StopReason.NO_RECIPE);
                return;
            }
            RecipeOption recipe = chooseRecipe(item, usable);
            int crafts = ceilDiv(needed, recipe.outputCount());
            steps.add(new CraftPlan.Step(item, crafts, depth, recipe));
            machines.addAll(recipe.machines());
            int leftover = crafts * recipe.outputCount() - needed;
            if (leftover > 0) {
                pool.merge(item, leftover, Integer::sum);
            }
            for (IngredientSlot slot : recipe.inputs()) {
                CraftPlan.Choice choice = chooseAlternative(item, slot); // before taking, so "owned" is still visible
                int remaining = takeFromPool(slot, slot.count() * crafts);
                if (remaining > 0) {
                    if (slot.alternatives().size() > 1) {
                        choices.add(choice);
                    }
                    obtain(choice.chosen(), remaining, depth + 1, nextPath);
                }
            }
        }

        /** Ingredients below the target: use what the pool still has, craft the rest. */
        private void obtain(IngredientKey item, int needed, int depth, List<IngredientKey> path) {
            if (!item.isItem()) {
                addMissing(item, needed, CraftPlan.StopReason.NOT_ITEM);
                return;
            }
            int remaining = take(item, needed);
            if (remaining > 0) {
                craft(item, remaining, depth, path);
            }
        }

        /** A recipe rule narrows the choice to its category; if nothing usable is left, it is ignored and noted. */
        private RecipeOption chooseRecipe(IngredientKey item, List<RecipeOption> usable) {
            Optional<String> category = preferences.recipeCategory(item);
            if (category.isEmpty()) {
                return choose(usable);
            }
            List<RecipeOption> ruled = usable.stream().filter(r -> r.category().equals(category.get())).toList();
            CraftPlan.ChoiceReason reason = ruled.isEmpty()
                    ? CraftPlan.ChoiceReason.RULE_UNUSABLE
                    : CraftPlan.ChoiceReason.RULE;
            choices.add(new CraftPlan.Choice(item, item, usable.size(), reason, category.get()));
            return choose(ruled.isEmpty() ? usable : ruled);
        }

        private CraftPlan.Choice chooseAlternative(IngredientKey owner, IngredientSlot slot) {
            List<IngredientKey> alternatives = slot.alternatives();
            Optional<IngredientKey> owned = alternatives.stream().filter(alt -> pool.getOrDefault(alt, 0) > 0).findFirst();
            if (owned.isPresent()) {
                return slotChoice(owner, owned.get(), slot, CraftPlan.ChoiceReason.OWNED);
            }
            Optional<IngredientKey> preferred = preferences.preferred().stream().filter(alternatives::contains).findFirst();
            if (preferred.isPresent()) {
                return slotChoice(owner, preferred.get(), slot, CraftPlan.ChoiceReason.RULE);
            }
            return slotChoice(owner, alternatives.get(0), slot, CraftPlan.ChoiceReason.DEFAULT);
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
            for (IngredientKey alt : slot.alternatives()) {
                remaining = take(alt, remaining);
                if (remaining == 0) {
                    break;
                }
            }
            return remaining;
        }

        private int take(IngredientKey item, int needed) {
            int owned = pool.getOrDefault(item, 0);
            int used = Math.min(owned, needed);
            if (used > 0) {
                pool.put(item, owned - used);
            }
            return needed - used;
        }

        private void addMissing(IngredientKey item, int count, CraftPlan.StopReason reason) {
            missing.merge(item, count, Integer::sum);
            reasons.putIfAbsent(item, reason);
            missingByGoal.computeIfAbsent(item, k -> new LinkedHashMap<>()).merge(goal, count, Integer::sum);
        }
    }

    private static CraftPlan.Choice slotChoice(IngredientKey owner, IngredientKey chosen, IngredientSlot slot,
                                               CraftPlan.ChoiceReason reason) {
        return new CraftPlan.Choice(owner, chosen, slot.alternatives().size(), reason, "");
    }

    private static List<IngredientKey> append(List<IngredientKey> path, IngredientKey item) {
        List<IngredientKey> next = new ArrayList<>(path);
        next.add(item);
        return List.copyOf(next);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}

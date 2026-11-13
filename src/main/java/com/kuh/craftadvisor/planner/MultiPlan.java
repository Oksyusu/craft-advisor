package com.kuh.craftadvisor.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of planning several goals against one shared inventory (see {@link CraftPlanner#planAll}).
 * Missing materials and machines are merged across goals. {@code missingByGoal} tells, for each missing
 * material, how much each goal added to it: goal index (0-based, into {@code goals}) to count.
 */
public record MultiPlan(List<Goal> goals, List<CraftPlan.Step> steps, List<CraftPlan.Missing> missing,
                        List<IngredientKey> machines, Map<IngredientKey, Map<Integer, Integer>> missingByGoal,
                        List<CraftPlan.Choice> choices) {
    public MultiPlan {
        goals = List.copyOf(goals);
        steps = List.copyOf(steps);
        missing = List.copyOf(missing);
        machines = List.copyOf(machines);
        Map<IngredientKey, Map<Integer, Integer>> byGoal = new LinkedHashMap<>();
        missingByGoal.forEach((item, counts) -> byGoal.put(item, Map.copyOf(counts)));
        missingByGoal = Collections.unmodifiableMap(byGoal);
        choices = List.copyOf(choices);
    }
}

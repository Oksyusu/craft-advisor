package com.kuh.craftadvisor.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.kuh.craftadvisor.planner.Goal;
import com.kuh.craftadvisor.planner.IngredientKey;

/**
 * The goals of {@code /ai add}, in the order they were added (earlier goals use the inventory first).
 * Kept in memory only, so it is gone when the game closes. Numbers shown to the player start at 1.
 */
final class GoalList {
    static final int MAX_GOALS = 20;

    private final List<Goal> goals = new ArrayList<>();

    /** Adds to the goal for the same item if there is one, else appends. Empty when a new goal would not fit. */
    Optional<Goal> add(IngredientKey item, int amount) {
        for (int i = 0; i < goals.size(); i++) {
            Goal goal = goals.get(i);
            if (goal.item().equals(item)) {
                Goal merged = new Goal(item, goal.amount() + amount);
                goals.set(i, merged);
                return Optional.of(merged);
            }
        }
        if (goals.size() >= MAX_GOALS) {
            return Optional.empty();
        }
        Goal added = new Goal(item, amount);
        goals.add(added);
        return Optional.of(added);
    }

    /** Removes the goal with this 1-based number; empty when there is none. */
    Optional<Goal> remove(int number) {
        if (number < 1 || number > goals.size()) {
            return Optional.empty();
        }
        return Optional.of(goals.remove(number - 1));
    }

    void clear() {
        goals.clear();
    }

    List<Goal> goals() {
        return List.copyOf(goals);
    }
}

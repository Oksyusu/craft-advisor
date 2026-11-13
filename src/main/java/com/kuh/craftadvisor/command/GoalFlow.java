package com.kuh.craftadvisor.command;

import java.util.List;
import java.util.Optional;

import com.kuh.craftadvisor.RecipePreferencesFile;
import com.kuh.craftadvisor.chat.ChatOut;
import com.kuh.craftadvisor.clipboard.MaterialOutput;
import com.kuh.craftadvisor.jei.CraftAdvisorJeiPlugin;
import com.kuh.craftadvisor.jei.JeiRecipeSource;
import com.kuh.craftadvisor.planner.CraftPlanner;
import com.kuh.craftadvisor.planner.Goal;
import com.kuh.craftadvisor.planner.IngredientKey;

import mezz.jei.api.runtime.IJeiRuntime;

/**
 * /ai add, list, remove and clear: a goal list planned together so the inventory is counted once.
 * Runs on the client thread. Never asks the AI for advice; only /ai add may ask it to pick an item.
 */
final class GoalFlow {
    /** Only touched on the client thread. */
    private static final GoalList GOALS = new GoalList();

    private GoalFlow() {
    }

    static void add(String text) {
        Optional<GoalInput> input = GoalInput.parse(text);
        if (input.isEmpty()) {
            ChatOut.error("craftadvisor.list.bad_amount", GoalInput.MIN_AMOUNT, GoalInput.MAX_AMOUNT);
            return;
        }
        if (input.get().name().isEmpty()) {
            ChatOut.info("craftadvisor.help.add");
            return;
        }
        int amount = input.get().amount();
        AdvisorFlow.findGoal(input.get().name(), item -> added(item, amount));
    }

    private static void added(IngredientKey item, int amount) {
        Optional<Goal> goal = GOALS.add(item, amount);
        if (goal.isEmpty()) {
            ChatOut.error("craftadvisor.list.full", GoalList.MAX_GOALS);
            return;
        }
        ChatOut.goalAdded(goal.get(), GOALS.goals().size());
    }

    /** Plans every goal with the inventory as it is now, and shows the merged result. */
    static void list() {
        List<Goal> goals = GOALS.goals();
        if (goals.isEmpty()) {
            ChatOut.info("craftadvisor.list.empty");
            return;
        }
        Optional<IJeiRuntime> runtime = CraftAdvisorJeiPlugin.runtime();
        if (runtime.isEmpty()) {
            ChatOut.error("craftadvisor.msg.jei_not_ready");
            return;
        }
        CraftPlanner planner = new CraftPlanner(new JeiRecipeSource(runtime.get()), RecipePreferencesFile.load());
        MaterialOutput.show(planner.planAll(goals, AdvisorFlow.inventory(runtime.get())));
    }

    static void remove(String text) {
        String number = text.strip();
        Optional<Goal> removed = number.matches("\\d{1,3}")
                ? GOALS.remove(Integer.parseInt(number))
                : Optional.empty();
        if (removed.isEmpty()) {
            ChatOut.error("craftadvisor.list.bad_number", number);
            return;
        }
        ChatOut.goalRemoved(removed.get(), GOALS.goals().size());
    }

    static void clear() {
        GOALS.clear();
        ChatOut.info("craftadvisor.list.cleared");
    }
}

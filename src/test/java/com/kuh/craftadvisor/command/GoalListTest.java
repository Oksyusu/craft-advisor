package com.kuh.craftadvisor.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kuh.craftadvisor.planner.Goal;
import com.kuh.craftadvisor.planner.IngredientKey;

class GoalListTest {
    private static final IngredientKey PISTON = IngredientKey.item("create:mechanical_piston");
    private static final IngredientKey SAW = IngredientKey.item("create:mechanical_saw");

    @Test
    void sameItemAddsToTheExistingGoal() {
        GoalList list = new GoalList();

        list.add(PISTON, 2);
        list.add(SAW, 1);
        Optional<Goal> merged = list.add(PISTON, 3);

        assertEquals(Optional.of(new Goal(PISTON, 5)), merged);
        assertEquals(List.of(new Goal(PISTON, 5), new Goal(SAW, 1)), list.goals());
    }

    @Test
    void newGoalIsRefusedWhenFullButExistingOneCanGrow() {
        GoalList list = new GoalList();
        for (int i = 0; i < GoalList.MAX_GOALS; i++) {
            list.add(IngredientKey.item("test:item" + i), 1);
        }

        assertEquals(Optional.empty(), list.add(PISTON, 1));
        assertEquals(Optional.of(new Goal(IngredientKey.item("test:item0"), 2)),
                list.add(IngredientKey.item("test:item0"), 1));
        assertEquals(GoalList.MAX_GOALS, list.goals().size());
    }

    @Test
    void removeUsesOneBasedNumbers() {
        GoalList list = new GoalList();
        list.add(PISTON, 2);
        list.add(SAW, 1);

        assertEquals(Optional.empty(), list.remove(0));
        assertEquals(Optional.empty(), list.remove(3));
        assertEquals(Optional.of(new Goal(PISTON, 2)), list.remove(1));
        assertEquals(List.of(new Goal(SAW, 1)), list.goals());
    }

    @Test
    void clearEmptiesTheList() {
        GoalList list = new GoalList();
        list.add(PISTON, 2);

        list.clear();

        assertEquals(List.of(), list.goals());
    }
}

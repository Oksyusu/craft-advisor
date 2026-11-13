package com.kuh.craftadvisor.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kuh.craftadvisor.planner.RecipePreferences.Problem;
import com.kuh.craftadvisor.planner.RecipePreferences.ProblemKind;

class RecipePreferencesTest {

    @Test
    void validLinesBecomeRules() {
        RecipePreferences.Parsed parsed = RecipePreferences.parse(List.of(
                "recipe create:andesite_alloy create:mixing",
                "prefer minecraft:spruce_planks",
                "PREFER minecraft:birch_planks"));

        assertEquals(List.of(), parsed.problems());
        assertEquals(Map.of(IngredientKey.item("create:andesite_alloy"), "create:mixing"),
                parsed.preferences().recipeCategories());
        assertEquals(List.of(IngredientKey.item("minecraft:spruce_planks"), IngredientKey.item("minecraft:birch_planks")),
                parsed.preferences().preferred());
    }

    @Test
    void commentsAndBlankLinesAreIgnored() {
        RecipePreferences.Parsed parsed = RecipePreferences.parse(List.of(
                "# recipe create:andesite_alloy create:mixing",
                "",
                "   ",
                "prefer minecraft:spruce_planks   # trailing comment"));

        assertEquals(List.of(), parsed.problems());
        assertEquals(Map.of(), parsed.preferences().recipeCategories());
        assertEquals(List.of(IngredientKey.item("minecraft:spruce_planks")), parsed.preferences().preferred());
    }

    @Test
    void unknownCommandIsReportedWithItsLineNumber() {
        RecipePreferences.Parsed parsed = RecipePreferences.parse(List.of(
                "# header",
                "avoid minecraft:oak_planks"));

        assertEquals(List.of(new Problem(2, ProblemKind.UNKNOWN_COMMAND, "avoid minecraft:oak_planks")),
                parsed.problems());
        assertEquals(RecipePreferences.NONE, parsed.preferences());
    }

    @Test
    void wrongArgumentCountsAreReportedAndSkipped() {
        RecipePreferences.Parsed parsed = RecipePreferences.parse(List.of(
                "recipe create:andesite_alloy",
                "prefer minecraft:oak_planks minecraft:spruce_planks",
                "prefer minecraft:birch_planks"));

        assertEquals(List.of(
                new Problem(1, ProblemKind.WRONG_ARGUMENT_COUNT, "recipe create:andesite_alloy"),
                new Problem(2, ProblemKind.WRONG_ARGUMENT_COUNT, "prefer minecraft:oak_planks minecraft:spruce_planks")),
                parsed.problems());
        assertEquals(List.of(IngredientKey.item("minecraft:birch_planks")), parsed.preferences().preferred());
    }

    @Test
    void idWithoutNamespaceIsReported() {
        RecipePreferences.Parsed parsed = RecipePreferences.parse(List.of("prefer spruce_planks", "prefer fluid:"));

        assertEquals(List.of(
                new Problem(1, ProblemKind.BAD_ID, "prefer spruce_planks"),
                new Problem(2, ProblemKind.BAD_ID, "prefer fluid:")), parsed.problems());
    }

    @Test
    void fluidPrefixMakesAFluidKey() {
        RecipePreferences.Parsed parsed = RecipePreferences.parse(List.of("prefer fluid:minecraft:water"));

        assertEquals(List.of(new IngredientKey(IngredientKey.FLUID, "minecraft:water")),
                parsed.preferences().preferred());
    }

    @Test
    void firstRecipeRuleForAnItemWins() {
        RecipePreferences preferences = RecipePreferences.parse(List.of(
                "recipe create:andesite_alloy create:mixing",
                "recipe create:andesite_alloy minecraft:crafting")).preferences();

        assertEquals(Optional.of("create:mixing"), preferences.recipeCategory(IngredientKey.item("create:andesite_alloy")));
    }
}

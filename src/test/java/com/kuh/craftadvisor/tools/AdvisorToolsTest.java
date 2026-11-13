package com.kuh.craftadvisor.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuh.craftadvisor.ai.OutgoingRequest;
import com.kuh.craftadvisor.ai.ToolCall;
import com.kuh.craftadvisor.ai.ToolResult;
import com.kuh.craftadvisor.ai.ToolSpec;
import com.kuh.craftadvisor.planner.CraftPlanner;
import com.kuh.craftadvisor.planner.IngredientKey;
import com.kuh.craftadvisor.planner.IngredientSlot;
import com.kuh.craftadvisor.planner.RecipeOption;
import com.kuh.craftadvisor.planner.RecipeSource;

class AdvisorToolsTest {
    private static final IngredientKey PISTON = IngredientKey.item("minecraft:piston");
    private static final IngredientKey WATER = new IngredientKey(IngredientKey.FLUID, "minecraft:water");
    private static final OutgoingRequest.Sharing SHARE_ALL = new OutgoingRequest.Sharing(true, true);
    private static final OutgoingRequest.Sharing NO_INVENTORY = new OutgoingRequest.Sharing(false, true);

    /** Piston from any of five planks, cobblestone and iron. */
    private static final RecipeSource RECIPES = item -> item.equals(PISTON)
            ? List.of(new RecipeOption(List.of(
                    new IngredientSlot(List.of(IngredientKey.item("minecraft:oak_planks"),
                            IngredientKey.item("minecraft:spruce_planks"), IngredientKey.item("minecraft:birch_planks"),
                            IngredientKey.item("minecraft:jungle_planks"), IngredientKey.item("minecraft:acacia_planks")), 3),
                    IngredientSlot.of("minecraft:cobblestone", 4),
                    IngredientSlot.of("minecraft:iron_ingot", 1)), 1, List.of(), "minecraft:crafting"))
            : List.of();
    private static final CraftPlanner PLANNER = new CraftPlanner(RECIPES);
    private static final Map<IngredientKey, Integer> INVENTORY = Map.of(
            IngredientKey.item("minecraft:cobblestone"), 10, IngredientKey.item("minecraft:diamond"), 7);
    private static final NameMatcher INDEX = new NameMatcher(List.of(
            new NameMatcher.Entry(PISTON, "Piston", ""),
            new NameMatcher.Entry(WATER, "Water", "")));

    private static AdvisorTools tools(OutgoingRequest.Sharing sharing) {
        return new AdvisorTools(new AdvisorTools.Data(() -> INDEX, RECIPES, PLANNER, () -> INVENTORY, IngredientKey::uid),
                sharing);
    }

    private static ToolCall call(String name, String input) {
        return new ToolCall("id1", name, JsonParser.parseString(input).getAsJsonObject());
    }

    private static String label(IngredientKey key) {
        return key.uid() + " (" + key + ")";
    }

    @Test
    void computeMissingReturnsThePlannerNumbersUnchanged() {
        ToolResult result = tools(SHARE_ALL).run(call(AdvisorTools.COMPUTE_MISSING, "{\"uid\":\"minecraft:piston\",\"amount\":3}"));

        String expected = OutgoingRequest.planJson(PLANNER.plan(PISTON, 3, INVENTORY), AdvisorToolsTest::label);
        assertFalse(result.isError());
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(result.content()));
    }

    @Test
    void computeMissingWithoutInventorySharingPlansFromAnEmptyInventory() {
        ToolResult result = tools(NO_INVENTORY).run(call(AdvisorTools.COMPUTE_MISSING, "{\"uid\":\"minecraft:piston\",\"amount\":3}"));

        String expected = OutgoingRequest.planJson(PLANNER.plan(PISTON, 3, Map.of()), AdvisorToolsTest::label);
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(result.content()));
    }

    @Test
    void inventoryToolIsNotOfferedWithoutInventorySharing() {
        List<String> offered = AdvisorTools.offered(NO_INVENTORY).stream().map(ToolSpec::name).toList();

        assertFalse(offered.contains(AdvisorTools.GET_INVENTORY));
        assertEquals(List.of(AdvisorTools.GET_INVENTORY), AdvisorTools.off(NO_INVENTORY));
        assertTrue(tools(NO_INVENTORY).run(call(AdvisorTools.GET_INVENTORY, "{}")).isError());
    }

    @Test
    void inventoryListsMostFirstWithTheKindCount() {
        JsonObject result = JsonParser.parseString(tools(SHARE_ALL).run(call(AdvisorTools.GET_INVENTORY, "{}")).content())
                .getAsJsonObject();

        assertEquals(2, result.get("totalKinds").getAsInt());
        assertEquals("minecraft:cobblestone", result.getAsJsonArray("items").get(0).getAsJsonObject().get("uid").getAsString());
    }

    @Test
    void recipesShowTheFirstThreeAlternativesAndTheirCount() {
        JsonObject result = JsonParser.parseString(
                tools(SHARE_ALL).run(call(AdvisorTools.GET_RECIPES, "{\"uid\":\"minecraft:piston\"}")).content())
                .getAsJsonObject();

        JsonObject planks = result.getAsJsonArray("recipes").get(0).getAsJsonObject()
                .getAsJsonArray("inputs").get(0).getAsJsonObject();
        assertEquals(3, planks.getAsJsonArray("alternatives").size());
        assertEquals(5, planks.get("alternativeCount").getAsInt());
        assertEquals(3, planks.get("amount").getAsInt());
        assertEquals(1, result.get("totalRecipes").getAsInt());
    }

    @Test
    void searchReturnsUidKindAndWhetherARecipeExists() {
        JsonArray result = JsonParser.parseString(
                tools(SHARE_ALL).run(call(AdvisorTools.SEARCH_ITEMS, "{\"query\":\"water\"}")).content()).getAsJsonArray();

        JsonObject water = result.get(0).getAsJsonObject();
        assertEquals("fluid:minecraft:water", water.get("uid").getAsString());
        assertEquals(IngredientKey.FLUID, water.get("kind").getAsString());
        assertFalse(water.get("hasRecipe").getAsBoolean());
    }

    @Test
    void badInputBecomesAnErrorResult() {
        AdvisorTools tools = tools(SHARE_ALL);

        assertTrue(tools.run(call(AdvisorTools.GET_RECIPES, "{\"uid\":\"minecraft:nothing\"}")).isError());
        assertTrue(tools.run(call(AdvisorTools.SEARCH_ITEMS, "{}")).isError());
        assertTrue(tools.run(call(AdvisorTools.COMPUTE_MISSING, "{\"uid\":\"minecraft:piston\",\"amount\":0}")).isError());
        assertTrue(tools.run(call(AdvisorTools.COMPUTE_MISSING, "{\"uid\":\"fluid:minecraft:water\",\"amount\":1}")).isError());
    }
}

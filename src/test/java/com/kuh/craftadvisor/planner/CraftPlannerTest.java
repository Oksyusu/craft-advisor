package com.kuh.craftadvisor.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kuh.craftadvisor.planner.CraftPlan.Missing;
import com.kuh.craftadvisor.planner.CraftPlan.StopReason;

class CraftPlannerTest {

    /** Small hand-written recipe book standing in for JEI. */
    private static final class Book implements RecipeSource {
        private final Map<ItemKey, List<RecipeOption>> recipes = new HashMap<>();

        Book add(String output, int outputCount, List<String> machines, IngredientSlot... inputs) {
            recipes.computeIfAbsent(ItemKey.of(output), k -> new ArrayList<>())
                    .add(new RecipeOption(List.of(inputs), outputCount, machines.stream().map(ItemKey::of).toList()));
            return this;
        }

        Book add(String output, int outputCount, IngredientSlot... inputs) {
            return add(output, outputCount, List.of(), inputs);
        }

        @Override
        public List<RecipeOption> recipesFor(ItemKey item) {
            return recipes.getOrDefault(item, List.of());
        }
    }

    private static IngredientSlot slot(String id, int count) {
        return IngredientSlot.of(id, count);
    }

    private static IngredientSlot anyOf(int count, String... ids) {
        return new IngredientSlot(Arrays.stream(ids).map(ItemKey::of).toList(), count);
    }

    private static Map<ItemKey, Integer> inv(Object... idCountPairs) {
        Map<ItemKey, Integer> map = new HashMap<>();
        for (int i = 0; i < idCountPairs.length; i += 2) {
            map.put(ItemKey.of((String) idCountPairs[i]), (Integer) idCountPairs[i + 1]);
        }
        return map;
    }

    private static Missing missing(String id, int count, StopReason reason) {
        return new Missing(ItemKey.of(id), count, reason);
    }

    private static Book vanillaPiston() {
        return new Book()
                .add("minecraft:piston", 1,
                        anyOf(3, "minecraft:oak_planks", "minecraft:spruce_planks"),
                        slot("minecraft:cobblestone", 4),
                        slot("minecraft:iron_ingot", 1),
                        slot("minecraft:redstone", 1))
                .add("minecraft:oak_planks", 4, slot("minecraft:oak_log", 1))
                .add("minecraft:iron_ingot", 1, List.of("minecraft:furnace"), slot("minecraft:raw_iron", 1))
                // decompress recipe: must not be chosen while crafting iron ingots for an iron block
                .add("minecraft:iron_ingot", 9, slot("minecraft:iron_block", 1))
                .add("minecraft:iron_block", 1, slot("minecraft:iron_ingot", 9));
    }

    @Test
    void emptyInventoryExpandsVanillaPistonToRawMaterials() {
        CraftPlan plan = new CraftPlanner(vanillaPiston()).plan(ItemKey.of("minecraft:piston"), 1, Map.of());

        assertEquals(List.of(
                missing("minecraft:oak_log", 1, StopReason.NO_RECIPE),
                missing("minecraft:cobblestone", 4, StopReason.NO_RECIPE),
                missing("minecraft:raw_iron", 1, StopReason.NO_RECIPE),
                missing("minecraft:redstone", 1, StopReason.NO_RECIPE)), plan.missing());
        assertEquals(List.of(ItemKey.of("minecraft:furnace")), plan.machines());
    }

    @Test
    void ownedItemsAreUsedBeforeCrafting() {
        CraftPlan plan = new CraftPlanner(vanillaPiston()).plan(ItemKey.of("minecraft:piston"), 1,
                inv("minecraft:oak_planks", 2, "minecraft:cobblestone", 10, "minecraft:iron_ingot", 1));

        assertEquals(List.of(
                missing("minecraft:oak_log", 1, StopReason.NO_RECIPE),
                missing("minecraft:redstone", 1, StopReason.NO_RECIPE)), plan.missing());
    }

    @Test
    void anyAlternativeOfATagSlotSatisfiesIt() {
        CraftPlan plan = new CraftPlanner(vanillaPiston()).plan(ItemKey.of("minecraft:piston"), 1,
                inv("minecraft:spruce_planks", 3));

        assertTrue(plan.missing().stream().noneMatch(m -> m.item().id().contains("log")));
    }

    @Test
    void craftsAreRoundedUpByOutputCount() {
        Book book = new Book()
                .add("minecraft:ladder", 3, slot("minecraft:stick", 7))
                .add("minecraft:stick", 4, slot("minecraft:oak_planks", 2))
                .add("minecraft:oak_planks", 4, slot("minecraft:oak_log", 1));

        CraftPlan plan = new CraftPlanner(book).plan(ItemKey.of("minecraft:ladder"), 3, Map.of());

        // 7 sticks -> 2 crafts -> 4 planks -> 1 log
        assertEquals(List.of(missing("minecraft:oak_log", 1, StopReason.NO_RECIPE)), plan.missing());
        assertEquals(2, plan.steps().stream()
                .filter(s -> s.item().id().equals("minecraft:stick")).findFirst().orElseThrow().crafts());
    }

    @Test
    void leftoverFromOneSlotCoversAnotherSlot() {
        Book book = new Book()
                .add("test:frame", 1, slot("minecraft:oak_planks", 2), slot("minecraft:oak_planks", 2))
                .add("minecraft:oak_planks", 4, slot("minecraft:oak_log", 1));

        CraftPlan plan = new CraftPlanner(book).plan(ItemKey.of("test:frame"), 1, Map.of());

        assertEquals(List.of(missing("minecraft:oak_log", 1, StopReason.NO_RECIPE)), plan.missing());
    }

    @Test
    void recipeThatLoopsBackIsSkippedAndReportedAsCycle() {
        Book book = new Book()
                .add("test:a", 1, slot("test:b", 1))
                .add("test:b", 1, slot("test:a", 1));

        CraftPlan plan = new CraftPlanner(book).plan(ItemKey.of("test:a"), 1, Map.of());

        assertEquals(List.of(missing("test:b", 1, StopReason.CYCLE)), plan.missing());
    }

    @Test
    void decompressRecipeIsNotChosenForIronIngot() {
        CraftPlan plan = new CraftPlanner(vanillaPiston()).plan(ItemKey.of("minecraft:iron_block"), 1, Map.of());

        assertEquals(List.of(missing("minecraft:raw_iron", 9, StopReason.NO_RECIPE)), plan.missing());
    }

    @Test
    void deepChainStopsAtMaxDepth() {
        Book book = new Book();
        for (int i = 0; i < 10; i++) {
            book.add("test:i" + i, 1, slot("test:i" + (i + 1), 1));
        }

        CraftPlan plan = new CraftPlanner(book).plan(ItemKey.of("test:i0"), 1, Map.of());

        assertEquals(List.of(missing("test:i" + CraftPlanner.MAX_DEPTH, 1, StopReason.MAX_DEPTH)), plan.missing());
    }

    @Test
    void recipeCoveredByInventoryIsPreferredOverJeiOrder() {
        Book book = new Book()
                .add("create:andesite_alloy", 2, slot("minecraft:andesite", 2), slot("minecraft:iron_nugget", 2))
                .add("create:andesite_alloy", 2, slot("minecraft:andesite", 2), slot("create:zinc_nugget", 2));

        CraftPlan plan = new CraftPlanner(book).plan(ItemKey.of("create:andesite_alloy"), 2,
                inv("minecraft:andesite", 2, "create:zinc_nugget", 2));

        assertEquals(List.of(), plan.missing());
    }

    @Test
    void createMachineRecipeCollectsMachines() {
        Book book = new Book()
                .add("create:iron_sheet", 1, List.of("create:mechanical_press"), slot("minecraft:iron_ingot", 1));

        CraftPlan plan = new CraftPlanner(book).plan(ItemKey.of("create:iron_sheet"), 4, Map.of());

        assertEquals(List.of(ItemKey.of("create:mechanical_press")), plan.machines());
        assertEquals(List.of(missing("minecraft:iron_ingot", 4, StopReason.NO_RECIPE)), plan.missing());
    }

    @Test
    void targetIsCraftedEvenWhenOwnedAndInventoryIsNotModified() {
        Map<ItemKey, Integer> inventory = inv("minecraft:piston", 5);

        CraftPlan plan = new CraftPlanner(vanillaPiston()).plan(ItemKey.of("minecraft:piston"), 1, inventory);

        assertEquals(4, plan.missing().size());
        assertEquals(inv("minecraft:piston", 5), inventory);
    }
}

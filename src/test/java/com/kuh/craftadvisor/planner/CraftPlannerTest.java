package com.kuh.craftadvisor.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kuh.craftadvisor.planner.CraftPlan.Choice;
import com.kuh.craftadvisor.planner.CraftPlan.ChoiceReason;
import com.kuh.craftadvisor.planner.CraftPlan.Missing;
import com.kuh.craftadvisor.planner.CraftPlan.StopReason;

class CraftPlannerTest {

    /** Small hand-written recipe book standing in for JEI. */
    private static final class Book implements RecipeSource {
        private static final String CRAFTING = "minecraft:crafting";
        private final Map<IngredientKey, List<RecipeOption>> recipes = new HashMap<>();

        Book add(String output, int outputCount, List<String> machines, IngredientSlot... inputs) {
            return addIn(CRAFTING, output, outputCount, machines, inputs);
        }

        Book addIn(String category, String output, int outputCount, List<String> machines, IngredientSlot... inputs) {
            recipes.computeIfAbsent(IngredientKey.item(output), k -> new ArrayList<>())
                    .add(new RecipeOption(List.of(inputs), outputCount,
                            machines.stream().map(IngredientKey::item).toList(), category));
            return this;
        }

        Book add(String output, int outputCount, IngredientSlot... inputs) {
            return add(output, outputCount, List.of(), inputs);
        }

        @Override
        public List<RecipeOption> recipesFor(IngredientKey item) {
            return recipes.getOrDefault(item, List.of());
        }
    }

    private static IngredientSlot slot(String id, int count) {
        return IngredientSlot.of(id, count);
    }

    private static IngredientSlot anyOf(int count, String... ids) {
        return new IngredientSlot(Arrays.stream(ids).map(IngredientKey::item).toList(), count);
    }

    private static Map<IngredientKey, Integer> inv(Object... idCountPairs) {
        Map<IngredientKey, Integer> map = new HashMap<>();
        for (int i = 0; i < idCountPairs.length; i += 2) {
            map.put(IngredientKey.item((String) idCountPairs[i]), (Integer) idCountPairs[i + 1]);
        }
        return map;
    }

    private static Missing missing(String id, int count, StopReason reason) {
        return new Missing(IngredientKey.item(id), count, reason);
    }

    private static IngredientKey fluid(String uid) {
        return new IngredientKey(IngredientKey.FLUID, uid);
    }

    private static RecipePreferences rules(String... lines) {
        RecipePreferences.Parsed parsed = RecipePreferences.parse(List.of(lines));
        assertEquals(List.of(), parsed.problems());
        return parsed.preferences();
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
        CraftPlan plan = new CraftPlanner(vanillaPiston()).plan(IngredientKey.item("minecraft:piston"), 1, Map.of());

        assertEquals(List.of(
                missing("minecraft:oak_log", 1, StopReason.NO_RECIPE),
                missing("minecraft:cobblestone", 4, StopReason.NO_RECIPE),
                missing("minecraft:raw_iron", 1, StopReason.NO_RECIPE),
                missing("minecraft:redstone", 1, StopReason.NO_RECIPE)), plan.missing());
        assertEquals(List.of(IngredientKey.item("minecraft:furnace")), plan.machines());
    }

    @Test
    void ownedItemsAreUsedBeforeCrafting() {
        CraftPlan plan = new CraftPlanner(vanillaPiston()).plan(IngredientKey.item("minecraft:piston"), 1,
                inv("minecraft:oak_planks", 2, "minecraft:cobblestone", 10, "minecraft:iron_ingot", 1));

        assertEquals(List.of(
                missing("minecraft:oak_log", 1, StopReason.NO_RECIPE),
                missing("minecraft:redstone", 1, StopReason.NO_RECIPE)), plan.missing());
    }

    @Test
    void anyAlternativeOfATagSlotSatisfiesIt() {
        CraftPlan plan = new CraftPlanner(vanillaPiston()).plan(IngredientKey.item("minecraft:piston"), 1,
                inv("minecraft:spruce_planks", 3));

        assertTrue(plan.missing().stream().noneMatch(m -> m.item().uid().contains("log")));
    }

    @Test
    void craftsAreRoundedUpByOutputCount() {
        Book book = new Book()
                .add("minecraft:ladder", 3, slot("minecraft:stick", 7))
                .add("minecraft:stick", 4, slot("minecraft:oak_planks", 2))
                .add("minecraft:oak_planks", 4, slot("minecraft:oak_log", 1));

        CraftPlan plan = new CraftPlanner(book).plan(IngredientKey.item("minecraft:ladder"), 3, Map.of());

        // 7 sticks -> 2 crafts -> 4 planks -> 1 log
        assertEquals(List.of(missing("minecraft:oak_log", 1, StopReason.NO_RECIPE)), plan.missing());
        assertEquals(2, plan.steps().stream()
                .filter(s -> s.item().uid().equals("minecraft:stick")).findFirst().orElseThrow().crafts());
    }

    @Test
    void leftoverFromOneSlotCoversAnotherSlot() {
        Book book = new Book()
                .add("test:frame", 1, slot("minecraft:oak_planks", 2), slot("minecraft:oak_planks", 2))
                .add("minecraft:oak_planks", 4, slot("minecraft:oak_log", 1));

        CraftPlan plan = new CraftPlanner(book).plan(IngredientKey.item("test:frame"), 1, Map.of());

        assertEquals(List.of(missing("minecraft:oak_log", 1, StopReason.NO_RECIPE)), plan.missing());
    }

    @Test
    void recipeThatLoopsBackIsSkippedAndReportedAsCycle() {
        Book book = new Book()
                .add("test:a", 1, slot("test:b", 1))
                .add("test:b", 1, slot("test:a", 1));

        CraftPlan plan = new CraftPlanner(book).plan(IngredientKey.item("test:a"), 1, Map.of());

        assertEquals(List.of(missing("test:b", 1, StopReason.CYCLE)), plan.missing());
    }

    @Test
    void decompressRecipeIsNotChosenForIronIngot() {
        CraftPlan plan = new CraftPlanner(vanillaPiston()).plan(IngredientKey.item("minecraft:iron_block"), 1, Map.of());

        assertEquals(List.of(missing("minecraft:raw_iron", 9, StopReason.NO_RECIPE)), plan.missing());
    }

    @Test
    void deepChainStopsAtMaxDepth() {
        Book book = new Book();
        for (int i = 0; i < 10; i++) {
            book.add("test:i" + i, 1, slot("test:i" + (i + 1), 1));
        }

        CraftPlan plan = new CraftPlanner(book).plan(IngredientKey.item("test:i0"), 1, Map.of());

        assertEquals(List.of(missing("test:i" + CraftPlanner.MAX_DEPTH, 1, StopReason.MAX_DEPTH)), plan.missing());
    }

    @Test
    void recipeCoveredByInventoryIsPreferredOverJeiOrder() {
        Book book = new Book()
                .add("create:andesite_alloy", 2, slot("minecraft:andesite", 2), slot("minecraft:iron_nugget", 2))
                .add("create:andesite_alloy", 2, slot("minecraft:andesite", 2), slot("create:zinc_nugget", 2));

        CraftPlan plan = new CraftPlanner(book).plan(IngredientKey.item("create:andesite_alloy"), 2,
                inv("minecraft:andesite", 2, "create:zinc_nugget", 2));

        assertEquals(List.of(), plan.missing());
    }

    @Test
    void createMachineRecipeCollectsMachines() {
        Book book = new Book()
                .add("create:iron_sheet", 1, List.of("create:mechanical_press"), slot("minecraft:iron_ingot", 1));

        CraftPlan plan = new CraftPlanner(book).plan(IngredientKey.item("create:iron_sheet"), 4, Map.of());

        assertEquals(List.of(IngredientKey.item("create:mechanical_press")), plan.machines());
        assertEquals(List.of(missing("minecraft:iron_ingot", 4, StopReason.NO_RECIPE)), plan.missing());
    }

    @Test
    void targetIsCraftedEvenWhenOwnedAndInventoryIsNotModified() {
        Map<IngredientKey, Integer> inventory = inv("minecraft:piston", 5);

        CraftPlan plan = new CraftPlanner(vanillaPiston()).plan(IngredientKey.item("minecraft:piston"), 1, inventory);

        assertEquals(4, plan.missing().size());
        assertEquals(inv("minecraft:piston", 5), inventory);
    }

    @Test
    void variantsWithTheSameRegistryIdAreCountedSeparately() {
        String healing = "minecraft:potion:minecraft:healing";
        String swiftness = "minecraft:potion:minecraft:swiftness";
        Book book = new Book().add("test:kit", 1, slot(healing, 1), slot(swiftness, 1));

        CraftPlan owned = new CraftPlanner(book).plan(IngredientKey.item("test:kit"), 1, inv(healing, 2));
        CraftPlan empty = new CraftPlanner(book).plan(IngredientKey.item("test:kit"), 1, Map.of());

        assertEquals(List.of(missing(swiftness, 1, StopReason.NO_RECIPE)), owned.missing());
        assertEquals(List.of(
                missing(healing, 1, StopReason.NO_RECIPE),
                missing(swiftness, 1, StopReason.NO_RECIPE)), empty.missing());
    }

    @Test
    void fluidInputIsMissingAsNotItemWithItsAmount() {
        Book book = new Book().addIn("create:mixing", "test:slurry", 1, List.of("create:mechanical_mixer"),
                new IngredientSlot(List.of(fluid("minecraft:water")), 250), slot("minecraft:clay_ball", 1));
        Map<IngredientKey, Integer> inventory = new HashMap<>(inv("minecraft:clay_ball", 2));
        inventory.put(fluid("minecraft:water"), 1000); // fluids are never taken from the inventory

        CraftPlan plan = new CraftPlanner(book).plan(IngredientKey.item("test:slurry"), 2, inventory);

        assertEquals(List.of(new Missing(fluid("minecraft:water"), 500, StopReason.NOT_ITEM)), plan.missing());
    }

    @Test
    void recipeRuleBeatsInventoryCoverage() {
        Book book = new Book()
                .add("create:andesite_alloy", 2, slot("minecraft:andesite", 2), slot("minecraft:iron_nugget", 2))
                .addIn("create:mixing", "create:andesite_alloy", 2, List.of("create:mechanical_mixer"),
                        slot("minecraft:andesite", 2), slot("create:zinc_nugget", 2));
        IngredientKey alloy = IngredientKey.item("create:andesite_alloy");

        CraftPlan plan = new CraftPlanner(book, rules("recipe create:andesite_alloy create:mixing"))
                .plan(alloy, 2, inv("minecraft:andesite", 2, "minecraft:iron_nugget", 2));

        assertEquals("create:mixing", plan.steps().get(0).recipe().category());
        assertEquals(List.of(missing("create:zinc_nugget", 2, StopReason.NO_RECIPE)), plan.missing());
        assertEquals(List.of(new Choice(alloy, alloy, 2, ChoiceReason.RULE, "create:mixing")), plan.choices());
    }

    @Test
    void recipeRuleThatLoopsFallsBackToDefaultAndIsNoted() {
        Book book = new Book()
                .add("test:a", 1, slot("test:b", 1))
                .addIn("create:mixing", "test:a", 1, List.of(), slot("test:a", 1), slot("test:c", 1));
        IngredientKey a = IngredientKey.item("test:a");

        CraftPlan plan = new CraftPlanner(book, rules("recipe test:a create:mixing")).plan(a, 1, Map.of());

        assertEquals("minecraft:crafting", plan.steps().get(0).recipe().category());
        assertEquals(List.of(missing("test:b", 1, StopReason.NO_RECIPE)), plan.missing());
        assertEquals(List.of(new Choice(a, a, 1, ChoiceReason.RULE_UNUSABLE, "create:mixing")), plan.choices());
    }

    private static Book planksBox() {
        return new Book()
                .add("test:box", 1, anyOf(3, "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks"))
                .add("minecraft:oak_planks", 4, slot("minecraft:oak_log", 1))
                .add("minecraft:spruce_planks", 4, slot("minecraft:spruce_log", 1))
                .add("minecraft:birch_planks", 4, slot("minecraft:birch_log", 1));
    }

    private static Choice plankChoice(String chosen, ChoiceReason reason) {
        return new Choice(IngredientKey.item("test:box"), IngredientKey.item(chosen), 3, reason, "");
    }

    @Test
    void preferRuleBeatsFirstAlternative() {
        CraftPlan plan = new CraftPlanner(planksBox(), rules("prefer minecraft:birch_planks"))
                .plan(IngredientKey.item("test:box"), 1, Map.of());

        assertEquals(List.of(missing("minecraft:birch_log", 1, StopReason.NO_RECIPE)), plan.missing());
        assertEquals(List.of(plankChoice("minecraft:birch_planks", ChoiceReason.RULE)), plan.choices());
    }

    @Test
    void ownedAlternativeBeatsPreferRule() {
        CraftPlan plan = new CraftPlanner(planksBox(), rules("prefer minecraft:birch_planks"))
                .plan(IngredientKey.item("test:box"), 1, inv("minecraft:spruce_planks", 1));

        assertEquals(List.of(missing("minecraft:spruce_log", 1, StopReason.NO_RECIPE)), plan.missing());
        assertEquals(List.of(plankChoice("minecraft:spruce_planks", ChoiceReason.OWNED)), plan.choices());
    }

    @Test
    void withoutRulesTheFirstAlternativeIsExpandedAndNoted() {
        CraftPlan plan = new CraftPlanner(planksBox()).plan(IngredientKey.item("test:box"), 1, Map.of());

        assertEquals(List.of(missing("minecraft:oak_log", 1, StopReason.NO_RECIPE)), plan.missing());
        assertEquals(List.of(plankChoice("minecraft:oak_planks", ChoiceReason.DEFAULT)), plan.choices());
    }

    // --- planAll (spec 04) ---

    private static Goal goal(String id, int amount) {
        return new Goal(IngredientKey.item(id), amount);
    }

    @Test
    void sharedInventoryIsCountedOnceAcrossGoals() {
        Book book = new Book()
                .add("test:press", 1, slot("minecraft:iron_ingot", 10))
                .add("test:drill", 1, slot("minecraft:iron_ingot", 10));

        MultiPlan plan = new CraftPlanner(book).planAll(
                List.of(goal("test:press", 1), goal("test:drill", 1)), inv("minecraft:iron_ingot", 10));

        assertEquals(List.of(missing("minecraft:iron_ingot", 10, StopReason.NO_RECIPE)), plan.missing());
        // the first goal took the stock, so the second one is short
        assertEquals(Map.of(IngredientKey.item("minecraft:iron_ingot"), Map.of(1, 10)), plan.missingByGoal());
    }

    @Test
    void leftoverOfFirstGoalIsUsedBySecondGoal() {
        Book book = new Book()
                .add("test:frame", 1, slot("minecraft:oak_planks", 3))
                .add("test:shelf", 1, slot("minecraft:oak_planks", 1))
                .add("minecraft:oak_planks", 4, slot("minecraft:oak_log", 1));

        MultiPlan plan = new CraftPlanner(book).planAll(
                List.of(goal("test:frame", 1), goal("test:shelf", 1)), Map.of());

        assertEquals(List.of(missing("minecraft:oak_log", 1, StopReason.NO_RECIPE)), plan.missing());
        assertEquals(1, plan.steps().stream()
                .filter(s -> s.item().uid().equals("minecraft:oak_planks")).mapToInt(CraftPlan.Step::crafts).sum());
    }

    @Test
    void missingByGoalAddsUpToEachMissingCount() {
        MultiPlan plan = new CraftPlanner(vanillaPiston()).planAll(
                List.of(goal("minecraft:piston", 2), goal("minecraft:iron_block", 1), goal("minecraft:piston", 1)),
                inv("minecraft:cobblestone", 5, "minecraft:iron_ingot", 4));

        assertEquals(plan.missing().stream().map(Missing::item).toList(), List.copyOf(plan.missingByGoal().keySet()));
        for (Missing m : plan.missing()) {
            int byGoals = plan.missingByGoal().get(m.item()).values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(m.count(), byGoals, m.item().uid());
        }
        // raw iron: piston x2 uses 2 owned ingots, the block 2 owned + 7 crafted, the last piston 1 crafted
        assertEquals(Map.of(1, 7, 2, 1), plan.missingByGoal().get(IngredientKey.item("minecraft:raw_iron")));
    }

    @Test
    void sameStepsOfDifferentGoalsAreMerged() {
        Book book = new Book()
                .add("test:frame", 1, slot("minecraft:oak_planks", 4))
                .add("minecraft:oak_planks", 4, slot("minecraft:oak_log", 1));

        MultiPlan plan = new CraftPlanner(book).planAll(
                List.of(goal("test:frame", 1), goal("test:frame", 2)), Map.of());

        assertEquals(List.of("test:frame", "minecraft:oak_planks"),
                plan.steps().stream().map(s -> s.item().uid()).toList());
        assertEquals(List.of(3, 3), plan.steps().stream().map(CraftPlan.Step::crafts).toList());
        assertEquals(List.of(missing("minecraft:oak_log", 3, StopReason.NO_RECIPE)), plan.missing());
    }

    @Test
    void singleGoalPlanAllMatchesPlan() {
        IngredientKey piston = IngredientKey.item("minecraft:piston");
        Map<IngredientKey, Integer> inventory = inv("minecraft:oak_planks", 2, "minecraft:iron_ingot", 1);
        CraftPlanner planner = new CraftPlanner(vanillaPiston());

        CraftPlan single = planner.plan(piston, 2, inventory);
        MultiPlan all = planner.planAll(List.of(new Goal(piston, 2)), inventory);

        assertEquals(single.steps(), all.steps());
        assertEquals(single.missing(), all.missing());
        assertEquals(single.machines(), all.machines());
        assertEquals(single.choices(), all.choices());
        for (Missing m : all.missing()) {
            assertEquals(Map.of(0, m.count()), all.missingByGoal().get(m.item()));
        }
    }

    @Test
    void emptyGoalListPlansNothing() {
        MultiPlan plan = new CraftPlanner(vanillaPiston()).planAll(List.of(), inv("minecraft:cobblestone", 1));

        assertEquals(List.of(), plan.steps());
        assertEquals(List.of(), plan.missing());
    }
}

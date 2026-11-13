package com.kuh.craftadvisor.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.kuh.craftadvisor.planner.CraftPlan;
import com.kuh.craftadvisor.planner.CraftPlanner;
import com.kuh.craftadvisor.planner.IngredientSlot;
import com.kuh.craftadvisor.planner.ItemKey;
import com.kuh.craftadvisor.planner.RecipeOption;

class OutgoingRequestTest {
    private static final String HOST = "api.anthropic.com";
    private static final Function<ItemKey, String> NAMES = ItemKey::id;
    private static final ItemKey PISTON = ItemKey.of("minecraft:piston");
    private static final OutgoingRequest.Sharing NO_INVENTORY = new OutgoingRequest.Sharing(false, true);
    private static final OutgoingRequest.Sharing NO_HISTORY = new OutgoingRequest.Sharing(true, false);

    /** Piston from cobblestone and iron; diamonds are owned but not part of the recipe tree. */
    private static final CraftPlanner PLANNER = new CraftPlanner(item -> item.equals(PISTON)
            ? List.of(new RecipeOption(List.of(IngredientSlot.of("minecraft:cobblestone", 4),
                    IngredientSlot.of("minecraft:iron_ingot", 1)), 1, List.of()))
            : List.of());
    private static final Map<ItemKey, Integer> INVENTORY = Map.of(
            ItemKey.of("minecraft:cobblestone"), 10, ItemKey.of("minecraft:diamond"), 7);

    @Test
    void adviceWithoutInventorySharingSendsTotalsFromAnEmptyInventory() {
        CraftPlan shown = PLANNER.plan(PISTON, 1, INVENTORY);

        OutgoingRequest request = OutgoingRequest.advice(HOST, "piston", shown, PLANNER, NO_INVENTORY, NAMES);

        String totals = OutgoingRequest.planJson(PLANNER.plan(PISTON, 1, Map.of()), NAMES);
        assertNotEquals(OutgoingRequest.planJson(shown, NAMES), totals);
        assertEquals("Plan computed by code:\n" + totals, section(request, "plan").text());
        assertNoInventory(request);
    }

    @Test
    void freeQuestionWithoutInventorySharingSendsNoInventorySummary() {
        OutgoingRequest request = OutgoingRequest.chat(HOST, "what next?", List.of(),
                "minecraft:diamond x7, minecraft:cobblestone x10", NO_INVENTORY);

        assertNoInventory(request);
        assertTrue(request.userText().contains(OutgoingRequest.NOT_SHARED));
    }

    @Test
    void freeQuestionWithoutHistorySharingSendsNoEarlierTurns() {
        List<AiProvider.Turn> history = List.of(
                new AiProvider.Turn(true, "earlier question"), new AiProvider.Turn(false, "earlier answer"));

        OutgoingRequest request = OutgoingRequest.chat(HOST, "what next?", history, "", NO_HISTORY);

        assertTrue(request.history().isEmpty());
        String body = new ClaudeProvider("key", "claude-opus-5-5", 2000).body(request);
        assertFalse(body.contains("earlier question"));
        assertFalse(body.contains("earlier answer"));
    }

    @Test
    void requestBodyNeverContainsTheApiKey() {
        String fakeKey = "sk-ant-test-FAKE-KEY-0123456789";
        ClaudeProvider provider = new ClaudeProvider(fakeKey, "claude-opus-5-5", 2000);
        CraftPlan plan = PLANNER.plan(PISTON, 1, INVENTORY);
        List<OutgoingRequest> requests = List.of(
                OutgoingRequest.pick(HOST, "piston", List.of("minecraft:piston | Piston")),
                OutgoingRequest.advice(HOST, "piston", plan, PLANNER, NO_HISTORY, NAMES),
                OutgoingRequest.chat(HOST, "what next?", List.of(new AiProvider.Turn(true, "hi")), "", NO_HISTORY));

        for (OutgoingRequest request : requests) {
            assertFalse(provider.body(request).contains(fakeKey), request.route().name());
        }
    }

    private static void assertNoInventory(OutgoingRequest request) {
        for (OutgoingRequest.Section section : request.sections()) {
            assertFalse(section.text().contains("diamond"), section.name());
            assertFalse(section.text().contains("x7"), section.name());
            assertFalse(section.text().contains("x10"), section.name());
        }
    }

    private static OutgoingRequest.Section section(OutgoingRequest request, String name) {
        return request.sections().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }
}

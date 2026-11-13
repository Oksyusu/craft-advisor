package com.kuh.craftadvisor.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.kuh.craftadvisor.planner.CraftPlan;
import com.kuh.craftadvisor.planner.CraftPlanner;
import com.kuh.craftadvisor.planner.IngredientKey;

/**
 * Everything one AI request carries besides the fixed system prompt. Sending and /ai preview use the same value.
 * Built from the question, JEI data and the plan only: player name, server address and coordinates are never inputs.
 */
public record OutgoingRequest(String host, Route route, List<AiProvider.Turn> history, List<Section> sections) {
    public static final String NOT_SHARED = "not shared";
    private static final int PLAN_STEPS_FOR_AI = 30;
    private static final Gson GSON = new Gson();

    public OutgoingRequest {
        history = List.copyOf(history);
        sections = List.copyOf(sections);
    }

    public enum Route { PICK, ADVICE, CHAT }

    /** A named part of the user message; {@code name} is only shown in the preview. */
    public record Section(String name, String text) {
        /** First {@code maxLines} lines, each cut to {@code maxChars}, for the preview. */
        public List<String> head(int maxLines, int maxChars) {
            return text.lines().limit(maxLines)
                    .map(line -> line.length() > maxChars ? line.substring(0, maxChars) + "..." : line)
                    .toList();
        }
    }

    /** What the player allows to leave the game (config {@code shareInventory}, {@code shareHistory}). */
    public record Sharing(boolean inventory, boolean history) {
    }

    public String userText() {
        return sections.stream().map(Section::text).collect(Collectors.joining("\n"));
    }

    public static OutgoingRequest pick(String host, String query, List<String> candidateLines) {
        return new OutgoingRequest(host, Route.PICK, List.of(), List.of(
                new Section("request", "Request: " + query),
                new Section("candidates", "Candidates (id | name):\n" + String.join("\n", candidateLines))));
    }

    /**
     * Advice on {@code shown}, the plan the player sees. When inventory is not shared, the AI gets the plan
     * re-computed from an empty inventory (total amounts), so owned items cannot be inferred from it.
     */
    public static OutgoingRequest advice(String host, String query, CraftPlan shown, CraftPlanner planner,
            Sharing sharing, Function<IngredientKey, String> names) {
        CraftPlan plan = sharing.inventory() ? shown : planner.plan(shown.target(), shown.amount(), Map.of());
        List<Section> sections = new ArrayList<>(List.of(
                new Section("request", "Player request: " + query),
                new Section("plan", "Plan computed by code:\n" + planJson(plan, names))));
        if (!sharing.inventory()) {
            sections.add(new Section("note", "Note: counts are totals; inventory not shared."));
        }
        return new OutgoingRequest(host, Route.ADVICE, List.of(), sections);
    }

    public static OutgoingRequest chat(String host, String question, List<AiProvider.Turn> history,
            String inventorySummary, Sharing sharing) {
        return new OutgoingRequest(host, Route.CHAT, sharing.history() ? history : List.of(), List.of(
                new Section("question", question),
                new Section("inventory", "(Inventory: " + (sharing.inventory() ? inventorySummary : NOT_SHARED) + ")")));
    }

    static String planJson(CraftPlan plan, Function<IngredientKey, String> names) {
        Map<String, Object> json = Map.of(
                "target", names.apply(plan.target()),
                "machines", plan.machines().stream().map(names).toList(),
                "toCraft", plan.steps().stream().limit(PLAN_STEPS_FOR_AI)
                        .map(s -> Map.of("item", names.apply(s.item()), "crafts", s.crafts(), "depth", s.depth()))
                        .toList(),
                "missing", plan.missing().stream()
                        .map(m -> Map.of("item", names.apply(m.item()), "count", m.count(),
                                "unit", IngredientKey.FLUID.equals(m.item().kind()) ? "mB" : "items",
                                "reason", m.reason().name().toLowerCase(Locale.ROOT)))
                        .toList(),
                "choices", plan.choices().stream().map(choice -> choiceJson(choice, names)).toList());
        return GSON.toJson(json);
    }

    private static Map<String, Object> choiceJson(CraftPlan.Choice choice, Function<IngredientKey, String> names) {
        String reason = choice.reason().name().toLowerCase(Locale.ROOT);
        if (choice.isRecipe()) {
            return Map.of("item", names.apply(choice.chosen()), "recipeRule", choice.recipeCategory(),
                    "usableRecipes", choice.alternativeCount(), "reason", reason);
        }
        return Map.of("slotOf", names.apply(choice.slotOwner()), "chosen", names.apply(choice.chosen()),
                "alternatives", choice.alternativeCount(), "reason", reason);
    }
}

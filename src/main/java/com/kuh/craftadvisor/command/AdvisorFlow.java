package com.kuh.craftadvisor.command;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.kuh.craftadvisor.ClientConfig;
import com.kuh.craftadvisor.RecipePreferencesFile;
import com.kuh.craftadvisor.ai.AiException;
import com.kuh.craftadvisor.ai.AiProvider;
import com.kuh.craftadvisor.ai.ClaudeProvider;
import com.kuh.craftadvisor.ai.ItemPick;
import com.kuh.craftadvisor.chat.ChatOut;
import com.kuh.craftadvisor.clipboard.MaterialOutput;
import com.kuh.craftadvisor.jei.CraftAdvisorJeiPlugin;
import com.kuh.craftadvisor.jei.ItemCandidates;
import com.kuh.craftadvisor.jei.Items;
import com.kuh.craftadvisor.jei.JeiRecipeSource;
import com.kuh.craftadvisor.planner.CraftPlan;
import com.kuh.craftadvisor.planner.CraftPlanner;
import com.kuh.craftadvisor.planner.IngredientKey;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * The /ai flow. Runs on the client thread; AI calls run in the background and hop back with
 * {@link Minecraft#execute}. Results that need no AI (JEI screen, materials) are shown first.
 */
final class AdvisorFlow {
    private static final int TARGET_AMOUNT = 1;
    private static final int MAX_HISTORY_TURNS = 12;
    private static final int INVENTORY_SUMMARY_ITEMS = 40;
    private static final int PLAN_STEPS_FOR_AI = 30;
    private static final Gson GSON = new Gson();
    /** Recent free-question turns; only touched on the client thread. */
    private static final Deque<AiProvider.Turn> HISTORY = new ArrayDeque<>();

    private AdvisorFlow() {
    }

    static void start(String query) {
        Optional<IJeiRuntime> runtime = CraftAdvisorJeiPlugin.runtime();
        if (runtime.isEmpty()) {
            ChatOut.error("craftadvisor.msg.jei_not_ready");
            return;
        }
        ItemCandidates.Result found = ItemCandidates.find(runtime.get(), query, ClientConfig.MAX_CANDIDATES.get());
        Optional<AiProvider> ai = provider();
        if (found.exact().isPresent()) {
            craft(runtime.get(), found.exact().get().key(), query, ai);
            return;
        }
        if (ai.isEmpty()) {
            ChatOut.error("craftadvisor.msg.need_key", query);
            return;
        }
        ChatOut.info("craftadvisor.msg.searching", query);
        if (found.candidates().isEmpty()) {
            askFreeQuestion(ai.get(), query);
            return;
        }
        List<String> lines = found.candidates().stream().map(c -> c.key().uid() + " | " + c.name()).toList();
        onClientThread(ai.get().pickItem(query, lines), pick -> onPicked(runtime.get(), ai.get(), query, found, pick));
    }

    private static void onPicked(IJeiRuntime runtime, AiProvider ai, String query, ItemCandidates.Result found, ItemPick pick) {
        boolean known = pick.wantsCraft()
                && found.candidates().stream().anyMatch(c -> c.key().uid().equals(pick.itemId()));
        if (known) {
            craft(runtime, IngredientKey.item(pick.itemId()), query, Optional.of(ai));
        } else {
            askFreeQuestion(ai, query);
        }
    }

    private static void craft(IJeiRuntime runtime, IngredientKey target, String query, Optional<AiProvider> ai) {
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack stack = Items.stackOf(target).orElse(ItemStack.EMPTY);
        if (minecraft.player == null || stack.isEmpty()) {
            ChatOut.error("craftadvisor.msg.unknown_item", target.uid());
            return;
        }
        Map<IngredientKey, Integer> inventory = Items.countAll(runtime.getIngredientManager(), minecraft.player.getInventory());
        CraftPlan plan = new CraftPlanner(new JeiRecipeSource(runtime), RecipePreferencesFile.load())
                .plan(target, TARGET_AMOUNT, inventory);

        runtime.getRecipesGui().show(runtime.getJeiHelpers().getFocusFactory()
                .createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, stack));
        MaterialOutput.show(plan);

        if (ai.isEmpty()) {
            ChatOut.info("craftadvisor.msg.advice_needs_key");
            return;
        }
        ChatOut.info("craftadvisor.msg.thinking");
        onClientThread(ai.get().advise(query, planJson(plan)), ChatOut::advice);
    }

    private static void askFreeQuestion(AiProvider ai, String question) {
        ChatOut.info("craftadvisor.msg.thinking");
        List<AiProvider.Turn> history = List.copyOf(HISTORY);
        onClientThread(ai.chat(history, question, inventorySummary()), answer -> {
            remember(new AiProvider.Turn(true, question));
            remember(new AiProvider.Turn(false, answer));
            ChatOut.advice(answer);
        });
    }

    private static void remember(AiProvider.Turn turn) {
        HISTORY.addLast(turn);
        while (HISTORY.size() > MAX_HISTORY_TURNS) {
            HISTORY.removeFirst();
        }
    }

    private static Optional<AiProvider> provider() {
        String key = ClientConfig.API_KEY.get();
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ClaudeProvider(key.strip(), ClientConfig.MODEL.get(), ClientConfig.ADVICE_MAX_TOKENS.get()));
    }

    /** Completes on the client thread; failures become a chat message. */
    private static <T> void onClientThread(CompletableFuture<T> future, Consumer<T> onSuccess) {
        future.whenComplete((value, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null) {
                showAiError(AiException.from(error));
            } else {
                onSuccess.accept(value);
            }
        }));
    }

    private static void showAiError(AiException error) {
        String key = "craftadvisor.ai." + error.kind().name().toLowerCase(java.util.Locale.ROOT);
        ChatOut.error(key, error.getMessage());
    }

    private static String planJson(CraftPlan plan) {
        Map<String, Object> json = Map.of(
                "target", name(plan.target()),
                "machines", plan.machines().stream().map(AdvisorFlow::name).toList(),
                "toCraft", plan.steps().stream().limit(PLAN_STEPS_FOR_AI)
                        .map(s -> Map.of("item", name(s.item()), "crafts", s.crafts(), "depth", s.depth()))
                        .toList(),
                "missing", plan.missing().stream()
                        .map(m -> Map.of("item", name(m.item()), "count", m.count(),
                                "unit", IngredientKey.FLUID.equals(m.item().kind()) ? "mB" : "items",
                                "reason", m.reason().name().toLowerCase(java.util.Locale.ROOT)))
                        .toList(),
                "choices", plan.choices().stream().map(AdvisorFlow::choiceJson).toList());
        return GSON.toJson(json);
    }

    private static Map<String, Object> choiceJson(CraftPlan.Choice choice) {
        String reason = choice.reason().name().toLowerCase(java.util.Locale.ROOT);
        if (choice.isRecipe()) {
            return Map.of("item", name(choice.chosen()), "recipeRule", choice.recipeCategory(),
                    "usableRecipes", choice.alternativeCount(), "reason", reason);
        }
        return Map.of("slotOf", name(choice.slotOwner()), "chosen", name(choice.chosen()),
                "alternatives", choice.alternativeCount(), "reason", reason);
    }

    private static String name(IngredientKey key) {
        return Items.nameOf(key).getString() + " (" + key + ")";
    }

    private static String inventorySummary() {
        Minecraft minecraft = Minecraft.getInstance();
        Optional<IJeiRuntime> runtime = CraftAdvisorJeiPlugin.runtime();
        if (minecraft.player == null || runtime.isEmpty()) {
            return "unknown";
        }
        return Items.countAll(runtime.get().getIngredientManager(), minecraft.player.getInventory()).entrySet().stream()
                .sorted(Map.Entry.<IngredientKey, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(INVENTORY_SUMMARY_ITEMS)
                .map(e -> Items.nameOf(e.getKey()).getString() + " x" + e.getValue())
                .collect(Collectors.joining(", "));
    }
}

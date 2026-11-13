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

import com.kuh.craftadvisor.ClientConfig;
import com.kuh.craftadvisor.RecipePreferencesFile;
import com.kuh.craftadvisor.ai.AiException;
import com.kuh.craftadvisor.ai.AiProvider;
import com.kuh.craftadvisor.ai.ClaudeProvider;
import com.kuh.craftadvisor.ai.ItemPick;
import com.kuh.craftadvisor.ai.OutgoingRequest;
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
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The /ai flow. Runs on the client thread; AI calls run in the background and hop back with
 * {@link Minecraft#execute}. Results that need no AI (JEI screen, materials) are shown first.
 */
final class AdvisorFlow {
    private static final int TARGET_AMOUNT = 1;
    private static final int MAX_HISTORY_TURNS = 12;
    private static final int INVENTORY_SUMMARY_ITEMS = 40;
    private static final int PREVIEW_LINES = 3;
    private static final int PREVIEW_LINE_CHARS = 120;
    /** Recent free-question turns; only touched on the client thread. */
    private static final Deque<AiProvider.Turn> HISTORY = new ArrayDeque<>();
    /** The one AI step held until /ai consent; only touched on the client thread. */
    private static Runnable pendingAfterConsent;

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
        if (!consented(() -> start(query))) {
            return;
        }
        ChatOut.info("craftadvisor.msg.searching", query);
        if (found.candidates().isEmpty()) {
            askFreeQuestion(ai.get(), query);
            return;
        }
        onClientThread(ai.get().pickItem(pickRequest(query, found)),
                pick -> onPicked(runtime.get(), ai.get(), query, found, pick));
    }

    /** Follows the same path as {@link #start} up to the first AI request and shows it; sends and opens nothing. */
    static void preview(String query) {
        Optional<IJeiRuntime> runtime = CraftAdvisorJeiPlugin.runtime();
        if (runtime.isEmpty()) {
            ChatOut.error("craftadvisor.msg.jei_not_ready");
            return;
        }
        ItemCandidates.Result found = ItemCandidates.find(runtime.get(), query, ClientConfig.MAX_CANDIDATES.get());
        Optional<AiProvider> ai = provider();
        if (found.exact().isPresent()) {
            if (ai.isEmpty()) {
                ChatOut.info("craftadvisor.preview.exact_only");
                return;
            }
            CraftPlanner planner = new CraftPlanner(new JeiRecipeSource(runtime.get()), RecipePreferencesFile.load());
            CraftPlan plan = planner.plan(found.exact().get().key(), TARGET_AMOUNT, inventory(runtime.get()));
            showPreview(OutgoingRequest.advice(ClaudeProvider.HOST, query, plan, planner, sharing(), AdvisorFlow::name));
            return;
        }
        if (ai.isEmpty()) {
            ChatOut.error("craftadvisor.msg.need_key", query);
            return;
        }
        showPreview(found.candidates().isEmpty() ? chatRequest(query) : pickRequest(query, found));
    }

    /** Saves consent for the current host and runs the held AI step, if any. */
    static void consent() {
        ClientConfig.CONSENTED_HOST.set(ClaudeProvider.HOST);
        ClientConfig.CONSENTED_HOST.save();
        ChatOut.info("craftadvisor.consent.saved", ClaudeProvider.HOST);
        Runnable pending = pendingAfterConsent;
        pendingAfterConsent = null;
        if (pending != null) {
            pending.run();
        }
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
        CraftPlanner planner = new CraftPlanner(new JeiRecipeSource(runtime), RecipePreferencesFile.load());
        CraftPlan plan = planner.plan(target, TARGET_AMOUNT, inventory(runtime));

        runtime.getRecipesGui().show(runtime.getJeiHelpers().getFocusFactory()
                .createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, stack));
        MaterialOutput.show(plan);

        if (ai.isEmpty()) {
            ChatOut.info("craftadvisor.msg.advice_needs_key");
            return;
        }
        OutgoingRequest request = OutgoingRequest.advice(ClaudeProvider.HOST, query, plan, planner, sharing(),
                AdvisorFlow::name);
        if (!consented(() -> advise(ai.get(), request))) {
            return;
        }
        advise(ai.get(), request);
    }

    private static void advise(AiProvider ai, OutgoingRequest request) {
        ChatOut.info("craftadvisor.msg.thinking");
        onClientThread(ai.answer(request), ChatOut::advice);
    }

    private static void askFreeQuestion(AiProvider ai, String question) {
        ChatOut.info("craftadvisor.msg.thinking");
        onClientThread(ai.answer(chatRequest(question)), answer -> {
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

    private static OutgoingRequest pickRequest(String query, ItemCandidates.Result found) {
        List<String> lines = found.candidates().stream().map(c -> c.key().uid() + " | " + c.name()).toList();
        return OutgoingRequest.pick(ClaudeProvider.HOST, query, lines);
    }

    private static OutgoingRequest chatRequest(String question) {
        return OutgoingRequest.chat(ClaudeProvider.HOST, question, List.copyOf(HISTORY), inventorySummary(), sharing());
    }

    private static OutgoingRequest.Sharing sharing() {
        return new OutgoingRequest.Sharing(ClientConfig.SHARE_INVENTORY.get(), ClientConfig.SHARE_HISTORY.get());
    }

    /** True when the player agreed to send to the current host; otherwise holds {@code pending} and explains. */
    private static boolean consented(Runnable pending) {
        if (ClaudeProvider.HOST.equals(ClientConfig.CONSENTED_HOST.get())) {
            return true;
        }
        pendingAfterConsent = pending;
        ChatOut.info("craftadvisor.consent.header", ClaudeProvider.HOST);
        ChatOut.info("craftadvisor.consent.sends");
        if (ClientConfig.SHARE_INVENTORY.get()) {
            ChatOut.info("craftadvisor.consent.sends_inventory");
        }
        if (ClientConfig.SHARE_HISTORY.get()) {
            ChatOut.info("craftadvisor.consent.sends_history");
        }
        ChatOut.info("craftadvisor.consent.not_sent");
        ChatOut.info("craftadvisor.consent.config");
        ChatOut.info("craftadvisor.consent.how");
        return false;
    }

    private static void showPreview(OutgoingRequest request) {
        String route = request.route().name().toLowerCase(java.util.Locale.ROOT);
        ChatOut.info("craftadvisor.preview.header", Component.translatable("craftadvisor.preview.route." + route));
        ChatOut.info("craftadvisor.preview.target", request.host(), ClientConfig.MODEL.get());
        if (request.route() == OutgoingRequest.Route.CHAT) {
            int chars = request.history().stream().mapToInt(turn -> turn.text().length()).sum();
            ChatOut.info("craftadvisor.preview.history", request.history().size(), chars);
        }
        for (OutgoingRequest.Section section : request.sections()) {
            ChatOut.info("craftadvisor.preview.section", section.name(), section.text().length());
            for (String line : section.head(PREVIEW_LINES, PREVIEW_LINE_CHARS)) {
                ChatOut.info("craftadvisor.preview.line", line);
            }
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

    private static String name(IngredientKey key) {
        return Items.nameOf(key).getString() + " (" + key + ")";
    }

    private static Map<IngredientKey, Integer> inventory(IJeiRuntime runtime) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? Map.of()
                : Items.countAll(runtime.getIngredientManager(), minecraft.player.getInventory());
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

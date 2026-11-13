package com.kuh.craftadvisor.command;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.kuh.craftadvisor.ClientConfig;
import com.kuh.craftadvisor.CraftAdvisor;
import com.kuh.craftadvisor.RecipePreferencesFile;
import com.kuh.craftadvisor.ai.AiException;
import com.kuh.craftadvisor.ai.AiProvider;
import com.kuh.craftadvisor.ai.ItemPick;
import com.kuh.craftadvisor.ai.OutgoingRequest;
import com.kuh.craftadvisor.ai.ProviderSettings;
import com.kuh.craftadvisor.ai.Providers;
import com.kuh.craftadvisor.ai.ToolLoop;
import com.kuh.craftadvisor.ai.ToolSpec;
import com.kuh.craftadvisor.chat.ChatOut;
import com.kuh.craftadvisor.chat.ChatText;
import com.kuh.craftadvisor.clipboard.MaterialOutput;
import com.kuh.craftadvisor.jei.CraftAdvisorJeiPlugin;
import com.kuh.craftadvisor.jei.ItemCandidates;
import com.kuh.craftadvisor.jei.Items;
import com.kuh.craftadvisor.jei.JeiRecipeSource;
import com.kuh.craftadvisor.jei.JeiTools;
import com.kuh.craftadvisor.planner.CraftPlan;
import com.kuh.craftadvisor.planner.CraftPlanner;
import com.kuh.craftadvisor.planner.IngredientKey;
import com.kuh.craftadvisor.tools.AdvisorTools;

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
    private static final int PREVIEW_LINES = 3;
    private static final int PREVIEW_LINE_CHARS = 120;
    private static final int SIMILAR_ITEMS = 5;
    /** Recent free-question turns; only touched on the client thread. */
    private static final Deque<AiProvider.Turn> HISTORY = new ArrayDeque<>();
    /** The one AI step held until /ai consent; only touched on the client thread. */
    private static Held pendingAfterConsent;

    /** An AI step waiting for /ai consent, with what the consent notice was shown for. */
    private record Held(String consentId, Runnable run) {
    }

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
            explainNoAi(query, found);
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
            showPreview(OutgoingRequest.advice(host(), query, plan, planner, sharing(), AdvisorFlow::name));
            return;
        }
        if (ai.isEmpty()) {
            explainNoAi(query, found);
            return;
        }
        showPreview(found.candidates().isEmpty() ? chatRequest(query, sharing()) : pickRequest(query, found));
    }

    /** Saves consent for the current host and runs the held AI step, if any. */
    static void consent() {
        ProviderSettings settings = Providers.current();
        if (settings.status() != ProviderSettings.Status.READY) {
            showProviderProblem(settings.status());
            return;
        }
        Held held = pendingAfterConsent;
        pendingAfterConsent = null;
        if (held != null && !held.consentId().equals(settings.consentId())) {
            // The settings changed after the notice: explain the current host instead of agreeing to it unseen.
            if (consented(held.run())) {
                held.run().run();
            }
            return;
        }
        ClientConfig.CONSENTED_HOST.set(settings.consentId());
        ClientConfig.CONSENTED_HOST.save();
        ChatOut.info("craftadvisor.consent.saved", settings.host());
        if (held != null) {
            held.run().run();
        }
    }

    /**
     * Item lookup for /ai add: an exact name or id, else the AI's pick among the name matches, else the AI's own
     * JEI search ({@link ItemSearch}) for names that only describe the item (with a key and consent). Unlike
     * {@link #start}, a question is never answered; it only says the item was not found.
     */
    static void findGoal(String query, Consumer<IngredientKey> onFound) {
        Optional<IJeiRuntime> runtime = CraftAdvisorJeiPlugin.runtime();
        if (runtime.isEmpty()) {
            ChatOut.error("craftadvisor.msg.jei_not_ready");
            return;
        }
        ItemCandidates.Result found = ItemCandidates.find(runtime.get(), query, ClientConfig.MAX_CANDIDATES.get());
        if (found.exact().isPresent()) {
            onFound.accept(found.exact().get().key());
            return;
        }
        Optional<AiProvider> ai = provider();
        if (ai.isEmpty()) {
            if (found.candidates().isEmpty()) {
                ChatOut.error("craftadvisor.list.not_found", query);
            } else {
                explainNoAi(query, found);
            }
            return;
        }
        if (!consented(() -> findGoal(query, onFound))) {
            return;
        }
        ChatOut.info("craftadvisor.msg.searching", query);
        if (found.candidates().isEmpty()) {
            ItemSearch.run(ai.get(), query, onFound);
            return;
        }
        onClientThread(ai.get().pickItem(pickRequest(query, found)), pick -> {
            boolean known = pick.wantsCraft()
                    && found.candidates().stream().anyMatch(c -> c.key().uid().equals(pick.itemId()));
            if (known) {
                onFound.accept(IngredientKey.item(pick.itemId()));
            } else {
                ItemSearch.run(ai.get(), query, onFound);
            }
        });
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
            showProviderProblem(Providers.current().status());
            return;
        }
        if (!consented(() -> adviseAfterConsent(target, query))) {
            return;
        }
        advise(ai.get(), adviceRequest(query, plan, planner));
    }

    /**
     * Held advice, run by /ai consent: rebuilt now so the current key, inventory and share settings apply
     * (not the ones from when it was held). The JEI screen and clipboard were already handled.
     */
    private static void adviseAfterConsent(IngredientKey target, String query) {
        Optional<IJeiRuntime> runtime = CraftAdvisorJeiPlugin.runtime();
        Optional<AiProvider> ai = provider();
        if (runtime.isEmpty() || ai.isEmpty()) {
            showProviderProblem(Providers.current().status());
            return;
        }
        CraftPlanner planner = new CraftPlanner(new JeiRecipeSource(runtime.get()), RecipePreferencesFile.load());
        CraftPlan plan = planner.plan(target, TARGET_AMOUNT, inventory(runtime.get()));
        advise(ai.get(), adviceRequest(query, plan, planner));
    }

    private static OutgoingRequest adviceRequest(String query, CraftPlan plan, CraftPlanner planner) {
        return OutgoingRequest.advice(host(), query, plan, planner, sharing(), AdvisorFlow::name);
    }

    private static void advise(AiProvider ai, OutgoingRequest request) {
        ChatOut.info("craftadvisor.msg.thinking");
        onClientThread(ai.answer(request), ChatOut::advice);
    }

    /** The tool loop: the AI looks up game data on the client thread until it answers. Only Q and A are kept. */
    private static void askFreeQuestion(AiProvider ai, String question) {
        ChatOut.info("craftadvisor.msg.thinking");
        OutgoingRequest.Sharing sharing = sharing();
        OutgoingRequest request = chatRequest(question, sharing);
        ToolLoop loop = new ToolLoop(ai, request.tools(), new JeiTools(sharing, AdvisorFlow::inventory),
                ClientConfig.MAX_TOOL_CALLS.get());
        long started = System.nanoTime();
        onClientThread(loop.run(request), answer -> {
            CraftAdvisor.LOGGER.debug("Free question answered in {} ms",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
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
        return OutgoingRequest.pick(host(), query, lines);
    }

    private static OutgoingRequest chatRequest(String question, OutgoingRequest.Sharing sharing) {
        return OutgoingRequest.chat(host(), question, List.copyOf(HISTORY), sharing,
                AdvisorTools.offered(sharing), AdvisorTools.off(sharing));
    }

    static OutgoingRequest.Sharing sharing() {
        return new OutgoingRequest.Sharing(ClientConfig.SHARE_INVENTORY.get(), ClientConfig.SHARE_HISTORY.get());
    }

    /**
     * True when requests stay on this computer or the player agreed to send to the current host (asked again when
     * the host changes); otherwise holds {@code pending} and explains.
     */
    private static boolean consented(Runnable pending) {
        ProviderSettings settings = Providers.current();
        if (settings.isLocal() || settings.consentId().equals(ClientConfig.CONSENTED_HOST.get())) {
            return true;
        }
        pendingAfterConsent = new Held(settings.consentId(), pending);
        ChatOut.info("craftadvisor.consent.header", settings.host());
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
            List<String> tools = request.tools().stream().map(ToolSpec::name).toList();
            ChatOut.info("craftadvisor.preview.tools", String.join(", ", tools));
            if (!request.toolsOff().isEmpty()) {
                ChatOut.info("craftadvisor.preview.tools_off", String.join(", ", request.toolsOff()));
            }
            ChatOut.info("craftadvisor.preview.tool_results");
        }
        for (OutgoingRequest.Section section : request.sections()) {
            ChatOut.info("craftadvisor.preview.section", section.name(), section.text().length());
            for (String line : section.head(PREVIEW_LINES, PREVIEW_LINE_CHARS)) {
                ChatOut.info("craftadvisor.preview.line", line);
            }
        }
    }

    /** Empty when the settings do not allow AI calls (offline, no key, bad baseUrl, no model name). */
    private static Optional<AiProvider> provider() {
        ProviderSettings settings = Providers.current();
        return settings.status() == ProviderSettings.Status.READY ? Optional.of(settings.create()) : Optional.empty();
    }

    /** Where AI requests go with the current settings. */
    static String host() {
        return Providers.current().host();
    }

    /** No exact item and no usable AI: similar names (offline or no key), or why the AI cannot be used. */
    private static void explainNoAi(String query, ItemCandidates.Result found) {
        ProviderSettings.Status status = Providers.current().status();
        switch (status) {
            case OFFLINE -> {
                if (found.candidates().isEmpty()) {
                    ChatOut.info("craftadvisor.offline.no_free_question");
                } else {
                    showSimilar(query, found);
                }
            }
            case NO_KEY -> {
                ChatOut.error("craftadvisor.msg.need_key", query);
                if (!found.candidates().isEmpty()) {
                    showSimilar(query, found);
                }
            }
            default -> showProviderProblem(status);
        }
    }

    private static void showSimilar(String query, ItemCandidates.Result found) {
        ChatOut.info("craftadvisor.offline.similar", query);
        found.candidates().stream().limit(SIMILAR_ITEMS)
                .forEach(candidate -> ChatOut.info("craftadvisor.offline.similar_line", ChatOut.link(candidate.key())));
    }

    /** Why AI advice and questions are off with the current settings. */
    private static void showProviderProblem(ProviderSettings.Status status) {
        switch (status) {
            case OFFLINE -> ChatOut.info("craftadvisor.provider.offline");
            case NO_KEY -> ChatOut.info("craftadvisor.msg.advice_needs_key");
            case BAD_BASE_URL -> ChatOut.error("craftadvisor.provider.bad_base_url");
            case NO_MODEL -> ChatOut.error("craftadvisor.provider.no_model");
            case READY -> {
            }
        }
    }

    /** Completes on the client thread; failures become a chat message. */
    static <T> void onClientThread(CompletableFuture<T> future, Consumer<T> onSuccess) {
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
        ChatOut.error(key, ChatText.clean(String.valueOf(error.getMessage()), ChatText.ERROR_MAX_CHARS));
    }

    private static String name(IngredientKey key) {
        return Items.nameOf(key).getString() + " (" + key + ")";
    }

    static Map<IngredientKey, Integer> inventory(IJeiRuntime runtime) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? Map.of()
                : Items.countAll(runtime.getIngredientManager(), minecraft.player.getInventory());
    }
}

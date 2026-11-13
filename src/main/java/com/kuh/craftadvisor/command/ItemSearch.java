package com.kuh.craftadvisor.command;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.kuh.craftadvisor.ClientConfig;
import com.kuh.craftadvisor.ai.AiProvider;
import com.kuh.craftadvisor.ai.OutgoingRequest;
import com.kuh.craftadvisor.ai.ToolLoop;
import com.kuh.craftadvisor.ai.ToolSpec;
import com.kuh.craftadvisor.chat.ChatOut;
import com.kuh.craftadvisor.jei.CraftAdvisorJeiPlugin;
import com.kuh.craftadvisor.jei.JeiTools;
import com.kuh.craftadvisor.jei.NameIndex;
import com.kuh.craftadvisor.planner.IngredientKey;
import com.kuh.craftadvisor.tools.AdvisorTools;
import com.kuh.craftadvisor.tools.NameMatcher;

/**
 * For /ai add with a name that only describes the item ("접착 피스톤" for the sticky piston): the AI searches JEI
 * itself with search_items, the only tool offered (no inventory, no history), and names a uid. Code accepts it
 * only if JEI has an item with that uid. Call after consent, on the client thread.
 */
final class ItemSearch {
    private static final int MAX_SEARCHES = 4;

    private ItemSearch() {
    }

    static void run(AiProvider ai, String query, Consumer<IngredientKey> onFound) {
        ChatOut.info("craftadvisor.list.searching_more", query);
        List<ToolSpec> tools = AdvisorTools.searchOnly();
        OutgoingRequest request = OutgoingRequest.findItem(AdvisorFlow.host(), ItemSearchAnswer.instruction(query), tools);
        int searches = Math.min(MAX_SEARCHES, ClientConfig.MAX_TOOL_CALLS.get());
        ToolLoop loop = new ToolLoop(ai, tools, new JeiTools(AdvisorFlow.sharing(), AdvisorFlow::inventory), searches);
        AdvisorFlow.onClientThread(loop.run(request), answer -> knownItem(answer)
                .ifPresentOrElse(onFound, () -> ChatOut.error("craftadvisor.list.not_found", query)));
    }

    private static Optional<IngredientKey> knownItem(String answer) {
        Optional<NameMatcher> items = CraftAdvisorJeiPlugin.runtime().map(r -> NameIndex.items(r.getIngredientManager()));
        if (items.isEmpty()) {
            return Optional.empty();
        }
        return ItemSearchAnswer.uids(answer).stream()
                .flatMap(uid -> items.get().byId(uid).stream())
                .map(NameMatcher.Entry::key)
                .filter(IngredientKey::isItem)
                .findFirst();
    }
}

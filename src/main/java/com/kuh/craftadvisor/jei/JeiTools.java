package com.kuh.craftadvisor.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.kuh.craftadvisor.CraftAdvisor;
import com.kuh.craftadvisor.RecipePreferencesFile;
import com.kuh.craftadvisor.ai.OutgoingRequest;
import com.kuh.craftadvisor.ai.ToolCall;
import com.kuh.craftadvisor.ai.ToolLoop;
import com.kuh.craftadvisor.ai.ToolResult;
import com.kuh.craftadvisor.planner.CraftPlanner;
import com.kuh.craftadvisor.planner.IngredientKey;
import com.kuh.craftadvisor.tools.AdvisorTools;

import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;

/**
 * Runs the AI's tool calls on the client thread, where JEI may be read. {@link #execute} only schedules the work
 * and returns at once, so neither the AI thread nor the client thread waits. One instance per question.
 */
public final class JeiTools implements ToolLoop.Executor {
    private final OutgoingRequest.Sharing sharing;
    private final Function<IJeiRuntime, Map<IngredientKey, Integer>> inventory;
    private IJeiRuntime toolsRuntime;
    private AdvisorTools tools;
    private int callCount;

    public JeiTools(OutgoingRequest.Sharing sharing, Function<IJeiRuntime, Map<IngredientKey, Integer>> inventory) {
        this.sharing = sharing;
        this.inventory = inventory;
    }

    @Override
    public CompletableFuture<List<ToolResult>> execute(List<ToolCall> calls) {
        CompletableFuture<List<ToolResult>> results = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> results.complete(runAll(calls)));
        return results;
    }

    private List<ToolResult> runAll(List<ToolCall> calls) {
        Optional<IJeiRuntime> runtime = CraftAdvisorJeiPlugin.runtime();
        List<ToolResult> results = new ArrayList<>();
        for (ToolCall call : calls) {
            callCount++;
            CraftAdvisor.LOGGER.debug("Tool call {}: {} {}", callCount, call.name(), call.input());
            results.add(runtime.map(r -> run(r, call))
                    .orElseGet(() -> ToolResult.error(call.id(), "JEI is not available right now.")));
        }
        return results;
    }

    private ToolResult run(IJeiRuntime runtime, ToolCall call) {
        try {
            return tools(runtime).run(call);
        } catch (RuntimeException e) {
            CraftAdvisor.LOGGER.warn("Tool {} failed", call.name(), e);
            return ToolResult.error(call.id(), "The tool failed in game (" + e.getClass().getSimpleName() + ").");
        }
    }

    /** Built on first use and again if JEI restarted; recipe lookups are cached for the whole question. */
    private AdvisorTools tools(IJeiRuntime runtime) {
        if (tools == null || toolsRuntime != runtime) {
            JeiRecipeSource recipes = new JeiRecipeSource(runtime);
            AdvisorTools.Data data = new AdvisorTools.Data(
                    () -> NameIndex.all(runtime.getIngredientManager()),
                    recipes,
                    new CraftPlanner(recipes, RecipePreferencesFile.load()),
                    () -> inventory.apply(runtime),
                    key -> Items.nameOf(key).getString());
            tools = new AdvisorTools(data, sharing);
            toolsRuntime = runtime;
        }
        return tools;
    }
}

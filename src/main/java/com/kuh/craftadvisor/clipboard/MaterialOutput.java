package com.kuh.craftadvisor.clipboard;

import com.kuh.craftadvisor.chat.ChatOut;
import com.kuh.craftadvisor.planner.CraftPlan;
import com.kuh.craftadvisor.planner.MultiPlan;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.fml.ModList;

/** Shows a plan: always in chat, and also on a held Create clipboard when Create is installed. */
public final class MaterialOutput {
    private static final boolean CREATE_LOADED = ModList.get().isLoaded("create");

    private MaterialOutput() {
    }

    public static void show(CraftPlan plan) {
        ChatOut.plan(plan);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !CREATE_LOADED || plan.missing().isEmpty()) {
            return;
        }
        // CreateClipboardWriter is only touched (and so only class-loaded) when Create is present.
        if (CreateClipboardWriter.isHoldingClipboard(player)) {
            CreateClipboardWriter.write(player, plan);
        }
    }

    /** The goal list: chat, and the merged missing materials on a held clipboard. */
    public static void show(MultiPlan plan) {
        ChatOut.goals(plan);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !CREATE_LOADED || plan.missing().isEmpty()) {
            return;
        }
        if (CreateClipboardWriter.isHoldingClipboard(player)) {
            CreateClipboardWriter.write(player, plan);
        }
    }
}

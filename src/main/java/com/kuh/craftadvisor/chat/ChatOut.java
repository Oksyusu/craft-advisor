package com.kuh.craftadvisor.chat;

import java.util.List;
import java.util.stream.Collectors;

import com.kuh.craftadvisor.jei.Items;
import com.kuh.craftadvisor.planner.CraftPlan;
import com.kuh.craftadvisor.planner.IngredientKey;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Local chat output only; nothing here is sent to the server. Call on the client thread. */
public final class ChatOut {
    private ChatOut() {
    }

    public static void info(String key, Object... args) {
        send(Component.translatable(key, args));
    }

    public static void error(String key, Object... args) {
        send(Component.translatable(key, args).withStyle(ChatFormatting.RED));
    }

    public static void advice(String text) {
        for (String line : text.strip().split("\\R")) {
            if (!line.isBlank()) {
                send(Component.literal(line).withStyle(ChatFormatting.AQUA));
            }
        }
    }

    public static void plan(CraftPlan plan) {
        info("craftadvisor.msg.target", Items.nameOf(plan.target()));
        if (!plan.machines().isEmpty()) {
            info("craftadvisor.msg.machines", joinNames(plan.machines()));
        }
        if (plan.missing().isEmpty()) {
            info("craftadvisor.msg.nothing_missing");
            return;
        }
        info("craftadvisor.msg.missing_header");
        for (CraftPlan.Missing missing : plan.missing()) {
            send(Component.translatable("craftadvisor.msg.missing_line",
                    Items.nameOf(missing.item()), missing.count(), reasonSuffix(missing.reason())));
        }
    }

    public static Component reasonSuffix(CraftPlan.StopReason reason) {
        return switch (reason) {
            case NO_RECIPE -> Component.empty();
            case CYCLE -> Component.translatable("craftadvisor.reason.cycle").withStyle(ChatFormatting.GRAY);
            case MAX_DEPTH -> Component.translatable("craftadvisor.reason.max_depth").withStyle(ChatFormatting.GRAY);
        };
    }

    private static String joinNames(List<IngredientKey> items) {
        return items.stream().map(item -> Items.nameOf(item).getString()).collect(Collectors.joining(", "));
    }

    private static void send(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        MutableComponent prefix = Component.literal("[Craft Advisor] ").withStyle(ChatFormatting.GOLD);
        minecraft.player.displayClientMessage(prefix.append(message), false);
    }
}

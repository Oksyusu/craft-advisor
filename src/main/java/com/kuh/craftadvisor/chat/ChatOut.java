package com.kuh.craftadvisor.chat;

import java.util.List;

import com.kuh.craftadvisor.jei.Items;
import com.kuh.craftadvisor.planner.CraftPlan;
import com.kuh.craftadvisor.planner.ItemKey;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
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
        info("craftadvisor.msg.target", link(plan.target()));
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
                    link(missing.item()), missing.count(), reasonSuffix(missing.reason())));
        }
    }

    public static Component reasonSuffix(CraftPlan.StopReason reason) {
        return switch (reason) {
            case NO_RECIPE -> Component.empty();
            case CYCLE -> Component.translatable("craftadvisor.reason.cycle").withStyle(ChatFormatting.GRAY);
            case MAX_DEPTH -> Component.translatable("craftadvisor.reason.max_depth").withStyle(ChatFormatting.GRAY);
        };
    }

    private static Component joinNames(List<ItemKey> items) {
        return ComponentUtils.formatList(items, Component.literal(", "), ChatOut::link);
    }

    /** Underlined item name that opens its JEI recipes via /ai show. Chat only, never the clipboard. */
    private static Component link(ItemKey item) {
        return Items.nameOf(item).copy().withStyle(style -> style
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ai show " + item.id()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("craftadvisor.msg.show_hover"))));
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

package com.kuh.craftadvisor.chat;

import java.util.List;
import java.util.Locale;

import com.kuh.craftadvisor.RecipePreferencesFile;
import com.kuh.craftadvisor.jei.Items;
import com.kuh.craftadvisor.jei.JeiRecipeSource;
import com.kuh.craftadvisor.planner.CraftPlan;
import com.kuh.craftadvisor.planner.IngredientKey;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** Local chat output only; nothing here is sent to the server. Call on the client thread. */
public final class ChatOut {
    private static final int MAX_CHOICE_LINES = 5;

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
            String line = isFluid(missing.item()) ? "craftadvisor.msg.missing_line_fluid" : "craftadvisor.msg.missing_line";
            send(Component.translatable(line,
                    link(missing.item()), missing.count(), reasonSuffix(missing.reason())));
        }
        choices(plan.choices());
    }

    /** Amount with its unit for plain text: "x4" for items, "1000 mB" for fluids. */
    public static String amountText(IngredientKey item, int count) {
        return isFluid(item) ? count + " mB" : "x" + count;
    }

    private static boolean isFluid(IngredientKey item) {
        return IngredientKey.FLUID.equals(item.kind());
    }

    private static void choices(List<CraftPlan.Choice> choices) {
        if (choices.isEmpty()) {
            return;
        }
        info("craftadvisor.msg.choices_header");
        choices.stream().map(ChatOut::choiceLine).distinct().limit(MAX_CHOICE_LINES).forEach(ChatOut::send);
        if (choices.stream().anyMatch(choice -> choice.reason() == CraftPlan.ChoiceReason.DEFAULT)) {
            send(Component.translatable("craftadvisor.msg.choices_hint", RecipePreferencesFile.DISPLAY_PATH)
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    private static Component choiceLine(CraftPlan.Choice choice) {
        String reason = choice.reason().name().toLowerCase(Locale.ROOT);
        if (choice.isRecipe()) {
            return Component.translatable("craftadvisor.msg.choice_recipe." + reason,
                    Items.nameOf(choice.chosen()), JeiRecipeSource.categoryName(choice.recipeCategory()));
        }
        return Component.translatable("craftadvisor.msg.choice_slot." + reason,
                Items.nameOf(choice.chosen()), choice.alternativeCount());
    }

    public static Component reasonSuffix(CraftPlan.StopReason reason) {
        return switch (reason) {
            case NO_RECIPE -> Component.empty();
            case CYCLE -> Component.translatable("craftadvisor.reason.cycle").withStyle(ChatFormatting.GRAY);
            case MAX_DEPTH -> Component.translatable("craftadvisor.reason.max_depth").withStyle(ChatFormatting.GRAY);
            case NOT_ITEM -> Component.empty();
        };
    }

    private static Component joinNames(List<IngredientKey> items) {
        return ComponentUtils.formatList(items, Component.literal(", "), ChatOut::link);
    }

    /**
     * Underlined item name that opens its JEI recipes via /ai show. Chat only, never the clipboard.
     * Non-item ingredients (fluids, ...) stay plain text: /ai show can only open item recipes for now.
     */
    public static Component link(IngredientKey item) {
        if (!item.isItem()) {
            return Items.nameOf(item);
        }
        return Items.nameOf(item).copy().withStyle(style -> style
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ai show " + item.uid()))
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

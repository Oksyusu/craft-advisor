package com.kuh.craftadvisor.clipboard;

import java.util.ArrayList;
import java.util.List;

import com.kuh.craftadvisor.chat.ChatOut;
import com.kuh.craftadvisor.jei.Items;
import com.kuh.craftadvisor.planner.CraftPlan;
import com.kuh.craftadvisor.planner.ItemKey;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEditPacket;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Appends missing materials to the Create clipboard in the main hand, using the same packet Create's
 * own clipboard screen sends. Only load this class when Create is installed.
 * Entries are plain text: a click event in any entry makes the server wipe the clipboard.
 */
final class CreateClipboardWriter {
    private CreateClipboardWriter() {
    }

    private static final ItemKey CLIPBOARD = ItemKey.of("create:clipboard");

    static boolean isHoldingClipboard(LocalPlayer player) {
        ItemStack held = player.getMainHandItem();
        return !held.isEmpty() && Items.keyOf(held).equals(CLIPBOARD);
    }

    static void write(LocalPlayer player, CraftPlan plan) {
        ItemStack held = player.getMainHandItem();
        List<List<ClipboardEntry>> pages = ClipboardEntry.readAll(held);
        int budget = ClipboardPages.MAX_PAGES - pages.size();
        if (budget <= 0) {
            ChatOut.error("craftadvisor.msg.clipboard_full");
            ChatOut.plan(plan);
            return;
        }

        String header = Component.translatable("craftadvisor.clipboard.header", Items.nameOf(plan.target())).getString();
        List<List<ClipboardEntry>> added = ClipboardPages.split(new Line(header, ItemStack.EMPTY, 0), lines(plan), budget)
                .stream()
                .map(page -> page.stream().map(Line::toEntry).toList())
                .toList();
        int firstNewPage = pages.size();
        List<List<ClipboardEntry>> allPages = new ArrayList<>(pages);
        allPages.addAll(added);

        ClipboardContent existing = held.getOrDefault(AllDataComponents.CLIPBOARD_CONTENT, ClipboardContent.EMPTY);
        ClipboardContent content = existing
                .setType(ClipboardType.WRITTEN)
                .setPages(allPages)
                .setPreviouslyOpenedPage(firstNewPage);
        PacketDistributor.sendToServer(new ClipboardEditPacket(player.getInventory().selected, content, null));

        int written = ClipboardPages.itemsWritten(added);
        ChatOut.info("craftadvisor.msg.clipboard_written", written, added.size());
        if (written < plan.missing().size()) {
            ChatOut.error("craftadvisor.msg.clipboard_truncated", plan.missing().size() - written);
        }
    }

    private static List<Line> lines(CraftPlan plan) {
        return plan.missing().stream()
                .map(m -> {
                    String text = Items.nameOf(m.item()).getString() + " x" + m.count()
                            + ChatOut.reasonSuffix(m.reason()).getString();
                    return new Line(text, Items.stackOf(m.item()).orElse(ItemStack.EMPTY), m.count());
                })
                .toList();
    }

    /** One clipboard line before it becomes a (mutable) Create entry. */
    private record Line(String text, ItemStack icon, int amount) {
        ClipboardEntry toEntry() {
            ClipboardEntry entry = new ClipboardEntry(false, Component.literal(text));
            if (!icon.isEmpty()) {
                entry.displayItem(icon, amount);
            }
            return entry;
        }
    }
}

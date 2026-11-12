package com.kuh.craftadvisor.jei;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.kuh.craftadvisor.planner.ItemKey;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Conversions between Minecraft items and planner {@link ItemKey}s. */
public final class Items {
    private Items() {
    }

    public static ItemKey keyOf(ItemStack stack) {
        return ItemKey.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    public static Optional<ItemStack> stackOf(ItemKey key) {
        ResourceLocation id = ResourceLocation.tryParse(key.id());
        if (id == null) {
            return Optional.empty();
        }
        return BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new);
    }

    public static Component nameOf(ItemKey key) {
        return stackOf(key).map(ItemStack::getHoverName).orElse(Component.literal(key.id()));
    }

    /** Item counts across the whole player inventory (main, armor, offhand). */
    public static Map<ItemKey, Integer> countAll(Inventory inventory) {
        Map<ItemKey, Integer> counts = new HashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                counts.merge(keyOf(stack), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }
}

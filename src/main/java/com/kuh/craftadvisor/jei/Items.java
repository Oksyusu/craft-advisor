package com.kuh.craftadvisor.jei;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.kuh.craftadvisor.planner.IngredientKey;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Conversions between Minecraft items and planner {@link IngredientKey}s. */
public final class Items {
    private Items() {
    }

    public static IngredientKey keyOf(ItemStack stack) {
        return IngredientKey.item(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    public static Optional<ItemStack> stackOf(IngredientKey key) {
        ResourceLocation id = ResourceLocation.tryParse(key.uid());
        if (id == null) {
            return Optional.empty();
        }
        return BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new);
    }

    public static Component nameOf(IngredientKey key) {
        return stackOf(key).map(ItemStack::getHoverName).orElse(Component.literal(key.uid()));
    }

    /** Item counts across the whole player inventory (main, armor, offhand). */
    public static Map<IngredientKey, Integer> countAll(Inventory inventory) {
        Map<IngredientKey, Integer> counts = new HashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                counts.merge(keyOf(stack), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }
}

package com.kuh.craftadvisor.jei;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.kuh.craftadvisor.planner.IngredientKey;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Conversions between JEI ingredients and planner {@link IngredientKey}s. Inventory stacks and recipe slots get
 * their keys from the same JEI uid rule, so a potion in the inventory matches the same potion in a recipe.
 * Ingredients that come from JEI (its ingredient list and recipe slots) are remembered, so a key can be turned
 * back into a stack or a name. Inventory stacks are not: their names could be player-written (book titles).
 * Call on the client thread.
 */
public final class Items {
    /** JEI stacks with any custom name removed: names shown and sent to the AI are default names. */
    private static final Map<IngredientKey, ItemStack> STACKS = new HashMap<>();
    private static final Map<IngredientKey, String> OTHER_NAMES = new HashMap<>();
    /** Non-item ingredients as JEI gave them, so their recipes can be looked up. */
    private static final Map<IngredientKey, ITypedIngredient<?>> OTHERS = new HashMap<>();

    private Items() {
    }

    /** Key of a stack JEI provided; remembered for {@link #stackOf} and {@link #nameOf}. */
    public static IngredientKey keyOf(IIngredientManager manager, ItemStack stack) {
        IngredientKey key = uidKey(manager, VanillaTypes.ITEM_STACK, stack);
        STACKS.computeIfAbsent(key, k -> withDefaultName(stack));
        return key;
    }

    /** Key of an ingredient JEI provided (any type); remembered for {@link #stackOf} and {@link #nameOf}. */
    public static <V> IngredientKey keyOf(IIngredientManager manager, ITypedIngredient<V> ingredient) {
        V value = ingredient.getIngredient();
        if (value instanceof ItemStack stack) {
            return keyOf(manager, stack);
        }
        IngredientKey key = uidKey(manager, ingredient.getType(), value);
        OTHER_NAMES.computeIfAbsent(key, k -> manager.getIngredientHelper(ingredient.getType()).getDisplayName(value));
        OTHERS.putIfAbsent(key, ingredient);
        return key;
    }

    /** The JEI ingredient behind a non-item key seen through JEI. */
    static Optional<ITypedIngredient<?>> typedOf(IngredientKey key) {
        return Optional.ofNullable(OTHERS.get(key));
    }

    /**
     * JEI's string uid in the recipe context, the context JEI itself uses to match recipes and inventory.
     * {@code getUid} would be the non-deprecated call, but it returns opaque objects that cannot be written down.
     */
    @SuppressWarnings("removal")
    private static <V> IngredientKey uidKey(IIngredientManager manager, IIngredientType<V> type, V ingredient) {
        IIngredientHelper<V> helper = manager.getIngredientHelper(type);
        String kind = kindOf(type);
        String uid = helper.getUniqueId(ingredient, UidContext.Recipe);
        String kindPrefix = kind + ":";
        if (!IngredientKey.ITEM.equals(kind) && uid.startsWith(kindPrefix)) {
            uid = uid.substring(kindPrefix.length()); // JEI fluid uids already start with "fluid:"
        }
        return new IngredientKey(kind, uid);
    }

    private static String kindOf(IIngredientType<?> type) {
        if (type == VanillaTypes.ITEM_STACK) {
            return IngredientKey.ITEM;
        }
        if (type == NeoForgeTypes.FLUID_STACK) {
            return IngredientKey.FLUID;
        }
        return type.getUid();
    }

    private static ItemStack withDefaultName(ItemStack stack) {
        ItemStack copy = stack.copyWithCount(1);
        copy.remove(DataComponents.CUSTOM_NAME);
        return copy;
    }

    /** Only items have a stack. Keys never seen through JEI fall back to the item registry. */
    public static Optional<ItemStack> stackOf(IngredientKey key) {
        if (!key.isItem()) {
            return Optional.empty();
        }
        ItemStack known = STACKS.get(key);
        if (known != null) {
            return Optional.of(known.copy());
        }
        ResourceLocation id = ResourceLocation.tryParse(key.uid());
        if (id == null) {
            return Optional.empty();
        }
        return BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new);
    }

    public static Component nameOf(IngredientKey key) {
        String other = OTHER_NAMES.get(key);
        if (other != null) {
            return Component.literal(other);
        }
        return stackOf(key).map(ItemStack::getHoverName).orElse(Component.literal(key.toString()));
    }

    /** Forget remembered ingredients, e.g. when JEI restarts for another world. */
    static void forget() {
        STACKS.clear();
        OTHER_NAMES.clear();
        OTHERS.clear();
    }

    /** Item counts across the whole player inventory (main, armor, offhand). Stacks are not remembered. */
    public static Map<IngredientKey, Integer> countAll(IIngredientManager manager, Inventory inventory) {
        Map<IngredientKey, Integer> counts = new HashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                counts.merge(uidKey(manager, VanillaTypes.ITEM_STACK, stack), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }
}

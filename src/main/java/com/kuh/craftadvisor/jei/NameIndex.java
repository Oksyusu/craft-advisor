package com.kuh.craftadvisor.jei;

import java.util.ArrayList;
import java.util.List;

import com.kuh.craftadvisor.planner.IngredientKey;
import com.kuh.craftadvisor.tools.NameMatcher;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;

/**
 * Builds a {@link NameMatcher} over JEI's ingredient list. Items also get their English name when the game runs
 * in another language: {@code en_us} is loaded once, separately from the current language. Names are default
 * names (see {@link Items}). Call on the client thread.
 */
public final class NameIndex {
    private static final String ENGLISH = "en_us";
    /** Loaded on first use, dropped with the JEI runtime. */
    private static Language english;

    private NameIndex() {
    }

    /** JEI items only (the paths that open recipes and plan). */
    public static NameMatcher items(IIngredientManager manager) {
        return new NameMatcher(itemEntries(manager));
    }

    /** Items and every other JEI ingredient kind (fluids, bees, ...); English names only for items. */
    public static NameMatcher all(IIngredientManager manager) {
        List<NameMatcher.Entry> entries = itemEntries(manager);
        for (IIngredientType<?> type : manager.getRegisteredIngredientTypes()) {
            if (type != VanillaTypes.ITEM_STACK) {
                addOthers(manager, type, entries);
            }
        }
        return new NameMatcher(entries);
    }

    static void forget() {
        english = null;
    }

    private static List<NameMatcher.Entry> itemEntries(IIngredientManager manager) {
        boolean needsEnglish = !ENGLISH.equals(Minecraft.getInstance().getLanguageManager().getSelected());
        List<NameMatcher.Entry> entries = new ArrayList<>();
        for (ItemStack stack : manager.getAllItemStacks()) {
            IngredientKey key = Items.keyOf(manager, stack);
            String english = needsEnglish ? Items.stackOf(key).map(NameIndex::englishName).orElse("") : "";
            entries.add(new NameMatcher.Entry(key, Items.nameOf(key).getString(), english));
        }
        return entries;
    }

    private static <V> void addOthers(IIngredientManager manager, IIngredientType<V> type, List<NameMatcher.Entry> out) {
        for (V value : manager.getAllIngredients(type)) {
            manager.createTypedIngredient(type, value, false).ifPresent(typed -> {
                IngredientKey key = Items.keyOf(manager, typed);
                out.add(new NameMatcher.Entry(key, Items.nameOf(key).getString(), ""));
            });
        }
    }

    /** The default name's translation key looked up in {@code en_us}; empty when it has none or takes arguments. */
    private static String englishName(ItemStack stack) {
        String key = stack.getHoverName().getContents() instanceof TranslatableContents translatable
                ? (translatable.getArgs().length == 0 ? translatable.getKey() : "")
                : stack.getDescriptionId();
        if (key.isEmpty()) {
            return "";
        }
        return englishLanguage().getOrDefault(key, "");
    }

    private static Language englishLanguage() {
        if (english == null) {
            Minecraft minecraft = Minecraft.getInstance();
            english = ClientLanguage.loadFrom(minecraft.getResourceManager(), List.of(ENGLISH), false);
        }
        return english;
    }
}

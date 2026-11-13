package com.kuh.craftadvisor.jei;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kuh.craftadvisor.planner.IngredientKey;
import com.kuh.craftadvisor.planner.IngredientSlot;
import com.kuh.craftadvisor.planner.RecipeOption;
import com.kuh.craftadvisor.planner.RecipeSource;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;

/**
 * Reads recipes from JEI for every category (crafting, smelting, Create machines, ...).
 * Only item slots are used; fluid and other ingredient types are ignored.
 * Must be used on the client thread. Results are cached per instance, so create one per /ai call.
 */
public final class JeiRecipeSource implements RecipeSource {
    private final IJeiRuntime runtime;
    private final Map<IngredientKey, List<RecipeOption>> cache = new HashMap<>();

    public JeiRecipeSource(IJeiRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public List<RecipeOption> recipesFor(IngredientKey item) {
        return cache.computeIfAbsent(item, this::lookup);
    }

    private List<RecipeOption> lookup(IngredientKey item) {
        Optional<ItemStack> stack = Items.stackOf(item);
        if (stack.isEmpty()) {
            return List.of();
        }
        IFocusFactory focusFactory = runtime.getJeiHelpers().getFocusFactory();
        IFocus<ItemStack> focus = focusFactory.createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, stack.get());
        IFocusGroup focusGroup = focusFactory.createFocusGroup(List.of(focus));
        IRecipeManager recipeManager = runtime.getRecipeManager();

        List<RecipeOption> options = new ArrayList<>();
        recipeManager.createRecipeCategoryLookup().limitFocus(List.of(focus)).get()
                .forEach(category -> collect(recipeManager, category, focus, focusGroup, item, options));
        return List.copyOf(options);
    }

    private static <T> void collect(IRecipeManager recipeManager, IRecipeCategory<T> category, IFocus<ItemStack> focus,
                                    IFocusGroup focusGroup, IngredientKey item, List<RecipeOption> out) {
        List<IngredientKey> machines = recipeManager.createRecipeCatalystLookup(category.getRecipeType())
                .getItemStack()
                .findFirst()
                .map(Items::keyOf)
                .map(List::of)
                .orElse(List.of());
        recipeManager.createRecipeLookup(category.getRecipeType()).limitFocus(List.of(focus)).get()
                .forEach(recipe -> recipeManager.createRecipeLayoutDrawable(category, recipe, focusGroup)
                        .flatMap(layout -> toOption(layout, item, machines))
                        .ifPresent(out::add));
    }

    private static <T> Optional<RecipeOption> toOption(IRecipeLayoutDrawable<T> layout, IngredientKey item, List<IngredientKey> machines) {
        var slots = layout.getRecipeSlotsView();
        List<IngredientSlot> inputs = new ArrayList<>();
        for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.INPUT)) {
            List<ItemStack> stacks = slot.getItemStacks().filter(s -> !s.isEmpty()).toList();
            if (stacks.isEmpty()) {
                continue; // empty grid cell, or a fluid / non-item ingredient
            }
            LinkedHashSet<IngredientKey> alternatives = new LinkedHashSet<>();
            stacks.forEach(s -> alternatives.add(Items.keyOf(s)));
            inputs.add(new IngredientSlot(List.copyOf(alternatives), stacks.get(0).getCount()));
        }
        int outputCount = slots.getSlotViews(RecipeIngredientRole.OUTPUT).stream()
                .flatMap(IRecipeSlotView::getItemStacks)
                .filter(s -> Items.keyOf(s).equals(item))
                .mapToInt(ItemStack::getCount)
                .findFirst()
                .orElse(1);
        if (inputs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RecipeOption(inputs, Math.max(1, outputCount), machines));
    }
}

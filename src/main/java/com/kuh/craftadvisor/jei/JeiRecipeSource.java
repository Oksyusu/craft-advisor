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
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Reads recipes from JEI for every category (crafting, smelting, Create machines, ...).
 * Input slots of every ingredient type are read (fluids in mB); the planner decides what to expand.
 * Must be used on the client thread. Results are cached per instance, so create one per /ai call.
 */
public final class JeiRecipeSource implements RecipeSource {
    private final IJeiRuntime runtime;
    private final Map<IngredientKey, List<RecipeOption>> cache = new HashMap<>();

    public JeiRecipeSource(IJeiRuntime runtime) {
        this.runtime = runtime;
    }

    /** JEI's title for a recipe category uid (e.g. "Mixing"); the uid itself when JEI does not know it. */
    public static Component categoryName(String categoryUid) {
        ResourceLocation uid = ResourceLocation.tryParse(categoryUid);
        return CraftAdvisorJeiPlugin.runtime()
                .filter(runtime -> uid != null)
                .map(IJeiRuntime::getRecipeManager)
                .flatMap(manager -> manager.getRecipeType(uid).map(type -> manager.getRecipeCategory(type).getTitle()))
                .orElse(Component.literal(categoryUid));
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
        IIngredientManager ingredients = runtime.getIngredientManager();

        List<RecipeOption> options = new ArrayList<>();
        recipeManager.createRecipeCategoryLookup().limitFocus(List.of(focus)).get()
                .forEach(category -> collect(recipeManager, ingredients, category, focus, focusGroup, item, options));
        return List.copyOf(options);
    }

    private static <T> void collect(IRecipeManager recipeManager, IIngredientManager ingredients,
                                    IRecipeCategory<T> category, IFocus<ItemStack> focus,
                                    IFocusGroup focusGroup, IngredientKey item, List<RecipeOption> out) {
        List<IngredientKey> machines = recipeManager.createRecipeCatalystLookup(category.getRecipeType())
                .getItemStack()
                .findFirst()
                .map(stack -> Items.keyOf(ingredients, stack))
                .map(List::of)
                .orElse(List.of());
        String categoryUid = category.getRecipeType().getUid().toString();
        recipeManager.createRecipeLookup(category.getRecipeType()).limitFocus(List.of(focus)).get()
                .forEach(recipe -> recipeManager.createRecipeLayoutDrawable(category, recipe, focusGroup)
                        .flatMap(layout -> toOption(ingredients, layout, item, machines, categoryUid))
                        .ifPresent(out::add));
    }

    private static <T> Optional<RecipeOption> toOption(IIngredientManager ingredients, IRecipeLayoutDrawable<T> layout,
                                                       IngredientKey item, List<IngredientKey> machines, String category) {
        var slots = layout.getRecipeSlotsView();
        List<IngredientSlot> inputs = new ArrayList<>();
        for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.INPUT)) {
            List<ITypedIngredient<?>> contents = slot.getAllIngredients().filter(i -> !isEmptyStack(i)).toList();
            if (contents.isEmpty()) {
                continue; // empty grid cell
            }
            LinkedHashSet<IngredientKey> alternatives = new LinkedHashSet<>();
            contents.forEach(i -> alternatives.add(Items.keyOf(ingredients, i)));
            inputs.add(new IngredientSlot(List.copyOf(alternatives), amountOf(ingredients, contents.get(0))));
        }
        int outputCount = slots.getSlotViews(RecipeIngredientRole.OUTPUT).stream()
                .flatMap(IRecipeSlotView::getItemStacks)
                .filter(s -> Items.keyOf(ingredients, s).equals(item))
                .mapToInt(ItemStack::getCount)
                .findFirst()
                .orElse(1);
        if (inputs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RecipeOption(inputs, Math.max(1, outputCount), machines, category));
    }

    private static boolean isEmptyStack(ITypedIngredient<?> ingredient) {
        return ingredient.getItemStack().map(ItemStack::isEmpty).orElse(false);
    }

    /** Item count, or the amount in the ingredient's own unit (mB for fluids). */
    private static <V> int amountOf(IIngredientManager ingredients, ITypedIngredient<V> ingredient) {
        long amount = ingredients.getIngredientHelper(ingredient.getType()).getAmount(ingredient.getIngredient());
        return Math.clamp(amount, 1, Integer.MAX_VALUE);
    }
}

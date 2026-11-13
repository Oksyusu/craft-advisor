package com.kuh.craftadvisor.command;

import java.util.List;
import java.util.Optional;

import com.kuh.craftadvisor.chat.ChatOut;
import com.kuh.craftadvisor.jei.CraftAdvisorJeiPlugin;
import com.kuh.craftadvisor.jei.Items;
import com.kuh.craftadvisor.planner.ItemKey;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;

/**
 * /ai show: only opens the JEI screen for one item, used by the chat links. No planning, no
 * clipboard, no AI call. Shows recipes that make the item, or its uses when nothing makes it.
 */
final class ShowRecipe {
    private ShowRecipe() {
    }

    static void show(String id) {
        Optional<IJeiRuntime> runtime = CraftAdvisorJeiPlugin.runtime();
        if (runtime.isEmpty()) {
            ChatOut.error("craftadvisor.msg.jei_not_ready");
            return;
        }
        ItemKey key = ItemKey.of(id);
        Optional<ItemStack> stack = Items.stackOf(key);
        if (stack.isEmpty()) {
            ChatOut.error("craftadvisor.msg.unknown_item", id);
            return;
        }
        for (RecipeIngredientRole role : List.of(RecipeIngredientRole.OUTPUT, RecipeIngredientRole.INPUT)) {
            IFocus<ItemStack> focus = runtime.get().getJeiHelpers().getFocusFactory()
                    .createFocus(role, VanillaTypes.ITEM_STACK, stack.get());
            if (hasRecipes(runtime.get(), focus)) {
                runtime.get().getRecipesGui().show(focus);
                return;
            }
        }
        ChatOut.info("craftadvisor.msg.no_jei_recipe", Items.nameOf(key));
    }

    private static boolean hasRecipes(IJeiRuntime runtime, IFocus<ItemStack> focus) {
        return runtime.getRecipeManager().createRecipeCategoryLookup().limitFocus(List.of(focus)).get()
                .findAny()
                .isPresent();
    }
}

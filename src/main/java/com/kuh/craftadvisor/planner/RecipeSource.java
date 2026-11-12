package com.kuh.craftadvisor.planner;

import java.util.List;

/** Recipes that produce an item, in JEI display order. Empty when nothing produces it. */
@FunctionalInterface
public interface RecipeSource {
    List<RecipeOption> recipesFor(ItemKey item);
}

package com.kuh.craftadvisor.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Player rules from the preferences file, one per line ({@code #} starts a comment):
 * <ul>
 *   <li>{@code recipe <item uid> <recipe category uid>}: craft this item with a recipe of this category.</li>
 *   <li>{@code prefer <ingredient uid>}: in a slot that lists it as an alternative, expand this one.
 *       Earlier {@code prefer} lines win.</li>
 * </ul>
 * Fluids are written with a kind prefix ({@code fluid:minecraft:water}); items have none.
 */
public record RecipePreferences(Map<IngredientKey, String> recipeCategories, List<IngredientKey> preferred) {
    public static final RecipePreferences NONE = new RecipePreferences(Map.of(), List.of());

    private static final String FLUID_PREFIX = IngredientKey.FLUID + ":";

    public RecipePreferences {
        recipeCategories = Map.copyOf(recipeCategories);
        preferred = List.copyOf(preferred);
    }

    public Optional<String> recipeCategory(IngredientKey item) {
        return Optional.ofNullable(recipeCategories.get(item));
    }

    /** A line that was skipped; {@code line} is 1-based. */
    public record Problem(int line, ProblemKind kind, String text) {
    }

    public enum ProblemKind {
        UNKNOWN_COMMAND,
        WRONG_ARGUMENT_COUNT,
        /** An id without a namespace, e.g. {@code spruce_planks} instead of {@code minecraft:spruce_planks}. */
        BAD_ID
    }

    public record Parsed(RecipePreferences preferences, List<Problem> problems) {
        public Parsed {
            problems = List.copyOf(problems);
        }
    }

    /** Bad lines are skipped and reported; the first rule for an item wins. */
    public static Parsed parse(List<String> lines) {
        Map<IngredientKey, String> recipes = new HashMap<>();
        Set<IngredientKey> preferred = new LinkedHashSet<>();
        List<Problem> problems = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String content = stripComment(lines.get(i));
            if (content.isEmpty()) {
                continue;
            }
            String[] words = content.split("\\s+");
            Optional<ProblemKind> problem = switch (words[0].toLowerCase(Locale.ROOT)) {
                case "recipe" -> parseRecipe(words, recipes);
                case "prefer" -> parsePrefer(words, preferred);
                default -> Optional.of(ProblemKind.UNKNOWN_COMMAND);
            };
            int lineNumber = i + 1;
            problem.ifPresent(kind -> problems.add(new Problem(lineNumber, kind, content)));
        }
        return new Parsed(new RecipePreferences(recipes, new ArrayList<>(preferred)), problems);
    }

    private static Optional<ProblemKind> parseRecipe(String[] words, Map<IngredientKey, String> out) {
        if (words.length != 3) {
            return Optional.of(ProblemKind.WRONG_ARGUMENT_COUNT);
        }
        Optional<IngredientKey> item = parseKey(words[1]);
        if (item.isEmpty() || !isId(words[2])) {
            return Optional.of(ProblemKind.BAD_ID);
        }
        out.putIfAbsent(item.get(), words[2]);
        return Optional.empty();
    }

    private static Optional<ProblemKind> parsePrefer(String[] words, Set<IngredientKey> out) {
        if (words.length != 2) {
            return Optional.of(ProblemKind.WRONG_ARGUMENT_COUNT);
        }
        Optional<IngredientKey> key = parseKey(words[1]);
        if (key.isEmpty()) {
            return Optional.of(ProblemKind.BAD_ID);
        }
        out.add(key.get());
        return Optional.empty();
    }

    private static Optional<IngredientKey> parseKey(String word) {
        boolean fluid = word.startsWith(FLUID_PREFIX);
        String uid = fluid ? word.substring(FLUID_PREFIX.length()) : word;
        if (!isId(uid)) {
            return Optional.empty();
        }
        return Optional.of(fluid ? new IngredientKey(IngredientKey.FLUID, uid) : IngredientKey.item(uid));
    }

    /** {@code namespace:path}, possibly followed by more {@code :} parts (JEI subtype uids). */
    private static boolean isId(String word) {
        int colon = word.indexOf(':');
        return colon > 0 && colon < word.length() - 1;
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return (hash >= 0 ? line.substring(0, hash) : line).strip();
    }
}

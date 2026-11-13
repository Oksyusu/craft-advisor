package com.kuh.craftadvisor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.kuh.craftadvisor.chat.ChatOut;
import com.kuh.craftadvisor.planner.RecipePreferences;

import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/**
 * {@code config/craftadvisor/recipe-preferences.txt}. Read again on every /ai (it is tiny), and created with
 * commented examples when missing. Bad lines are skipped, logged, and shown in chat once until they change.
 * Call on the client thread.
 */
public final class RecipePreferencesFile {
    public static final String DISPLAY_PATH = "config/craftadvisor/recipe-preferences.txt";
    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("craftadvisor").resolve("recipe-preferences.txt");
    private static final String EXAMPLE = """
            # Craft Advisor recipe preferences. One rule per line; '#' starts a comment. Read again on every /ai.
            #
            # recipe <item uid> <recipe category uid> : craft this item with this kind of recipe
            #recipe create:andesite_alloy create:mixing
            #
            # prefer <ingredient uid> : in a slot that accepts it, use this one (earlier lines win)
            #prefer minecraft:spruce_planks
            #
            # Fluids take a kind prefix, e.g. fluid:minecraft:water
            """;

    /** Problems already shown in chat. */
    private static List<RecipePreferences.Problem> shown = List.of();

    private RecipePreferencesFile() {
    }

    public static RecipePreferences load() {
        try {
            if (Files.notExists(PATH)) {
                Files.createDirectories(PATH.getParent());
                Files.writeString(PATH, EXAMPLE);
            }
            RecipePreferences.Parsed parsed = RecipePreferences.parse(Files.readAllLines(PATH));
            warnOnce(parsed.problems());
            return parsed.preferences();
        } catch (IOException e) {
            CraftAdvisor.LOGGER.warn("Could not read {}", PATH, e);
            ChatOut.error("craftadvisor.msg.rules_unreadable", DISPLAY_PATH);
            return RecipePreferences.NONE;
        }
    }

    private static void warnOnce(List<RecipePreferences.Problem> problems) {
        if (problems.equals(shown)) {
            return;
        }
        shown = problems;
        for (RecipePreferences.Problem problem : problems) {
            CraftAdvisor.LOGGER.warn("{} line {} skipped ({}): {}", PATH, problem.line(), problem.kind(), problem.text());
            ChatOut.error("craftadvisor.msg.rules_bad_line", problem.line(),
                    Component.translatable("craftadvisor.rules." + problem.kind().name().toLowerCase(Locale.ROOT)));
        }
    }
}

package com.kuh.craftadvisor.jei;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.kuh.craftadvisor.planner.ItemKey;

import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;

/**
 * Finds JEI items that a free-text question may refer to, using the display name in the current
 * game language and the registry id. The AI then picks one of these candidates.
 */
public final class ItemCandidates {
    private static final int MIN_TOKEN_LENGTH = 2;
    private static final int NAME_IN_QUERY_SCORE = 100;
    private static final int TOKEN_HIT_SCORE = 10;

    public record Candidate(ItemKey key, String name) {
    }

    public record Result(List<Candidate> candidates, Optional<Candidate> exact) {
    }

    private ItemCandidates() {
    }

    public static Result find(IJeiRuntime runtime, String query, int limit) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        Map<ItemKey, Candidate> all = new LinkedHashMap<>();
        for (ItemStack stack : runtime.getIngredientManager().getAllItemStacks()) {
            ItemKey key = Items.keyOf(stack);
            all.putIfAbsent(key, new Candidate(key, stack.getHoverName().getString()));
        }

        Optional<Candidate> exact = all.values().stream()
                .filter(c -> c.key().id().equalsIgnoreCase(normalized) || c.name().equalsIgnoreCase(normalized))
                .findFirst();

        List<String> tokens = List.of(normalized.split("\\s+")).stream()
                .filter(t -> t.length() >= MIN_TOKEN_LENGTH)
                .toList();
        List<Scored> scored = new ArrayList<>();
        for (Candidate candidate : all.values()) {
            int score = score(candidate, normalized, tokens);
            if (score > 0) {
                scored.add(new Scored(candidate, score));
            }
        }
        List<Candidate> top = scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(limit)
                .map(Scored::candidate)
                .toList();
        return new Result(top, exact);
    }

    private record Scored(Candidate candidate, int score) {
    }

    /** Whole item name inside the question ("기계식 피스톤 만들고 싶어") beats single word hits. */
    private static int score(Candidate candidate, String query, List<String> tokens) {
        String name = candidate.name().toLowerCase(Locale.ROOT);
        String id = candidate.key().id().toLowerCase(Locale.ROOT);
        if (name.length() >= MIN_TOKEN_LENGTH && query.contains(name)) {
            return NAME_IN_QUERY_SCORE + name.length();
        }
        int hits = 0;
        for (String token : tokens) {
            if (name.contains(token) || id.contains(token)) {
                hits++;
            }
        }
        return hits * TOKEN_HIT_SCORE;
    }
}

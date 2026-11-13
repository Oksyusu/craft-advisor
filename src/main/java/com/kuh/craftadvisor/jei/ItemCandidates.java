package com.kuh.craftadvisor.jei;

import java.util.List;
import java.util.Optional;

import com.kuh.craftadvisor.planner.IngredientKey;
import com.kuh.craftadvisor.tools.NameMatcher;

import mezz.jei.api.runtime.IJeiRuntime;

/**
 * Finds JEI items that a free-text question may refer to, by name in the current game language, English name
 * and registry id (see {@link NameMatcher}). The AI then picks one of these candidates.
 */
public final class ItemCandidates {

    public record Candidate(IngredientKey key, String name) {
    }

    public record Result(List<Candidate> candidates, Optional<Candidate> exact) {
    }

    private ItemCandidates() {
    }

    public static Result find(IJeiRuntime runtime, String query, int limit) {
        NameMatcher matcher = NameIndex.items(runtime.getIngredientManager());
        List<Candidate> top = matcher.search(query, limit).stream().map(ItemCandidates::candidate).toList();
        return new Result(top, matcher.exact(query).map(ItemCandidates::candidate));
    }

    private static Candidate candidate(NameMatcher.Entry entry) {
        return new Candidate(entry.key(), entry.name());
    }
}

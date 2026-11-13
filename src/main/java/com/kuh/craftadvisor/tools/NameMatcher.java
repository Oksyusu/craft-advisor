package com.kuh.craftadvisor.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.kuh.craftadvisor.planner.IngredientKey;

/**
 * Finds JEI ingredients a free-text query may refer to, by display name, English name and id. Spaces are ignored
 * when comparing names ("기계식피스톤" = "기계식 피스톤") and {@code _} in ids reads as a space ("iron bee").
 * Entries keep the order they were added in (JEI order), which breaks ties.
 */
public final class NameMatcher {
    private static final int MIN_LENGTH = 2;
    private static final int NAME_IN_QUERY_SCORE = 100;
    private static final int QUERY_IN_NAME_SCORE = 60;
    private static final int TOKEN_HIT_SCORE = 10;

    /** {@code nameEn} is empty when the game already runs in English or the name has no English entry. */
    public record Entry(IngredientKey key, String name, String nameEn) {
        public String id() {
            return key.toString();
        }

        private List<String> names() {
            return nameEn.isEmpty() ? List.of(name) : List.of(name, nameEn);
        }
    }

    private final List<Entry> entries;
    private final Map<String, Entry> byId = new HashMap<>();

    /** Later entries with an id already seen are dropped. */
    public NameMatcher(List<Entry> entries) {
        List<Entry> unique = new ArrayList<>();
        for (Entry entry : entries) {
            if (byId.putIfAbsent(entry.id(), entry) == null) {
                unique.add(entry);
            }
        }
        this.entries = List.copyOf(unique);
    }

    /** The entry whose id ({@link IngredientKey#toString()}) is exactly {@code id}. */
    public Optional<Entry> byId(String id) {
        return Optional.ofNullable(byId.get(id.strip()));
    }

    /** An id match (any case), else the first entry whose name or English name equals the query, spaces ignored. */
    public Optional<Entry> exact(String query) {
        String trimmed = query.strip();
        Optional<Entry> id = entries.stream().filter(e -> e.id().equalsIgnoreCase(trimmed)).findFirst();
        if (id.isPresent()) {
            return id;
        }
        String compact = compact(query);
        return entries.stream()
                .filter(e -> e.names().stream().anyMatch(name -> compact(name).equals(compact)))
                .findFirst();
    }

    /** Best matches first, at most {@code limit}. */
    public List<Entry> search(String query, int limit) {
        String normalized = normalize(query);
        String compact = compact(query);
        List<String> tokens = List.of(normalized.split(" ")).stream().filter(t -> t.length() >= MIN_LENGTH).toList();
        List<Scored> scored = new ArrayList<>();
        for (Entry entry : entries) {
            int score = score(entry, compact, tokens);
            if (score > 0) {
                scored.add(new Scored(entry, score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(limit)
                .map(Scored::entry)
                .toList();
    }

    private record Scored(Entry entry, int score) {
    }

    /** Whole name inside the query ("기계식 피스톤 만들고 싶어") beats the query inside a name, which beats word hits. */
    private static int score(Entry entry, String compactQuery, List<String> tokens) {
        int best = 0;
        for (String name : entry.names()) {
            String compactName = compact(name);
            if (compactName.length() >= MIN_LENGTH && compactQuery.contains(compactName)) {
                best = Math.max(best, NAME_IN_QUERY_SCORE + compactName.length());
            } else if (compactQuery.length() >= MIN_LENGTH && compactName.contains(compactQuery)) {
                best = Math.max(best, QUERY_IN_NAME_SCORE);
            }
        }
        if (best > 0) {
            return best;
        }
        List<String> texts = new ArrayList<>(entry.names().stream().map(NameMatcher::normalize).toList());
        texts.add(normalize(entry.id()));
        int hits = 0;
        for (String token : tokens) {
            if (texts.stream().anyMatch(text -> text.contains(token))) {
                hits++;
            }
        }
        return hits * TOKEN_HIT_SCORE;
    }

    /** Lower case, {@code _} as a space, runs of spaces as one. */
    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('_', ' ').strip().replaceAll("\\s+", " ");
    }

    private static String compact(String text) {
        return normalize(text).replace(" ", "");
    }
}

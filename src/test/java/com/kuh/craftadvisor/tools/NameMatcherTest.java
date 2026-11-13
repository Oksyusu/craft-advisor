package com.kuh.craftadvisor.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kuh.craftadvisor.planner.IngredientKey;

class NameMatcherTest {
    private static final NameMatcher.Entry PISTON =
            entry("minecraft:piston", "피스톤", "Piston");
    private static final NameMatcher.Entry MECHANICAL_PISTON =
            entry("create:mechanical_piston", "기계식 피스톤", "Mechanical Piston");
    private static final NameMatcher.Entry IRON_INGOT =
            entry("minecraft:iron_ingot", "철 주괴", "Iron Ingot");
    private static final NameMatcher.Entry BEE_EGG =
            entry("minecraft:bee_spawn_egg", "벌 생성 알", "Bee Spawn Egg");
    /** A non-item kind: display name only, no English index. */
    private static final NameMatcher.Entry IRON_BEE =
            new NameMatcher.Entry(new IngredientKey("productive_bee", "productivebees:iron_bee"), "철 꿀벌", "");
    private static final NameMatcher MATCHER =
            new NameMatcher(List.of(PISTON, MECHANICAL_PISTON, IRON_INGOT, BEE_EGG, IRON_BEE));

    private static NameMatcher.Entry entry(String id, String name, String nameEn) {
        return new NameMatcher.Entry(IngredientKey.item(id), name, nameEn);
    }

    @Test
    void nameWithoutSpacesFindsTheSpacedName() {
        assertEquals(MECHANICAL_PISTON, MATCHER.exact("기계식피스톤").orElseThrow());
        assertEquals(MECHANICAL_PISTON, MATCHER.search("기계식피스톤 만들고 싶어", 5).get(0));
    }

    @Test
    void englishNameFindsTheItemInAnotherGameLanguage() {
        assertEquals(MECHANICAL_PISTON, MATCHER.exact("Mechanical Piston").orElseThrow());
        assertEquals(MECHANICAL_PISTON, MATCHER.search("how to make a mechanical piston", 5).get(0));
    }

    @Test
    void underscoresInIdsMatchWordsOfTheQuery() {
        List<NameMatcher.Entry> found = MATCHER.search("iron bee", 5);

        assertEquals(IRON_BEE, found.get(0));
        assertTrue(found.contains(IRON_INGOT));
    }

    @Test
    void idIsAnExactMatchInAnyCase() {
        assertEquals(PISTON, MATCHER.exact("Minecraft:Piston").orElseThrow());
        assertEquals(IRON_BEE, MATCHER.byId("productive_bee:productivebees:iron_bee").orElseThrow());
    }

    @Test
    void wholeNameInTheQueryRanksAboveShorterNames() {
        assertEquals(List.of(MECHANICAL_PISTON, PISTON), MATCHER.search("기계식 피스톤", 2));
    }
}

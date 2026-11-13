package com.kuh.craftadvisor.planner;

/**
 * An ingredient as JEI tells them apart: its ingredient kind ({@code item}, {@code fluid}, or a kind a mod
 * registered) and JEI's unique id. Items without subtypes use their registry id, e.g. {@code minecraft:piston};
 * variants such as potions get a longer uid, so they are never merged.
 */
public record IngredientKey(String kind, String uid) {
    public static final String ITEM = "item";
    public static final String FLUID = "fluid";

    public IngredientKey {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("ingredient kind must not be blank");
        }
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("ingredient uid must not be blank");
        }
    }

    public static IngredientKey item(String uid) {
        return new IngredientKey(ITEM, uid);
    }

    public boolean isItem() {
        return ITEM.equals(kind);
    }

    /** Items read as their uid, other kinds as {@code kind:uid} (e.g. {@code fluid:minecraft:water}). */
    @Override
    public String toString() {
        return isItem() ? uid : kind + ":" + uid;
    }
}

package com.kuh.craftadvisor.planner;

/** Registry id of an item, e.g. {@code minecraft:piston}. Item components are ignored. */
public record ItemKey(String id) {
    public ItemKey {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("item id must not be blank");
        }
    }

    public static ItemKey of(String id) {
        return new ItemKey(id);
    }

    @Override
    public String toString() {
        return id;
    }
}

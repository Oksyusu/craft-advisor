package com.kuh.craftadvisor.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class GoalInputTest {

    private static Optional<GoalInput> parsed(String name, int amount) {
        return Optional.of(new GoalInput(name, amount));
    }

    @Test
    void xSuffixIsTheCount() {
        assertEquals(parsed("기계식 피스톤", 4), GoalInput.parse("기계식 피스톤 x4"));
        assertEquals(parsed("minecraft:piston", 2), GoalInput.parse("minecraft:piston X2"));
    }

    @Test
    void multiplicationSignIsTheCount() {
        assertEquals(parsed("Mechanical Piston", 12), GoalInput.parse("Mechanical Piston ×12"));
    }

    @Test
    void gaeSuffixIsTheCount() {
        assertEquals(parsed("기계식 피스톤", 4), GoalInput.parse("기계식 피스톤 4개"));
        assertEquals(parsed("기계식 피스톤", 4), GoalInput.parse("기계식 피스톤 4 개"));
    }

    @Test
    void withoutCountTheAmountIsOne() {
        assertEquals(parsed("기계식 피스톤", 1), GoalInput.parse("  기계식 피스톤  "));
        // digits that are part of the name are not a count
        assertEquals(parsed("test:tier4", 1), GoalInput.parse("test:tier4"));
        assertEquals(parsed("box x", 1), GoalInput.parse("box x"));
    }

    @Test
    void countsOutsideOneTo999AreRejected() {
        assertEquals(Optional.empty(), GoalInput.parse("기계식 피스톤 x0"));
        assertEquals(Optional.empty(), GoalInput.parse("기계식 피스톤 x1000"));
        assertEquals(Optional.empty(), GoalInput.parse("기계식 피스톤 0개"));
        assertEquals(Optional.empty(), GoalInput.parse("piston x99999999999"));
        assertEquals(parsed("piston", 999), GoalInput.parse("piston x999"));
    }

    @Test
    void countAloneLeavesTheNameBlank() {
        assertEquals(parsed("", 4), GoalInput.parse("x4"));
    }
}

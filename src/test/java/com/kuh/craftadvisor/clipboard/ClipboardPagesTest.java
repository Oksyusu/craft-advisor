package com.kuh.craftadvisor.clipboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class ClipboardPagesTest {

    private static List<Integer> items(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    @Test
    void everyPageStartsWithHeaderAndHoldsAtMostFourteenItems() {
        List<List<Integer>> pages = ClipboardPages.split(-1, items(20), ClipboardPages.MAX_PAGES);

        assertEquals(2, pages.size());
        assertEquals(15, pages.get(0).size());
        assertEquals(-1, pages.get(1).get(0));
        assertEquals(7, pages.get(1).size());
        assertEquals(20, ClipboardPages.itemsWritten(pages));
    }

    @Test
    void itemsBeyondThePageBudgetAreDropped() {
        List<List<Integer>> pages = ClipboardPages.split(-1, items(100), 2);

        assertEquals(2, pages.size());
        assertEquals(28, ClipboardPages.itemsWritten(pages));
    }

    @Test
    void noItemsMeansNoPages() {
        assertEquals(List.of(), ClipboardPages.split(-1, List.of(), ClipboardPages.MAX_PAGES));
    }
}

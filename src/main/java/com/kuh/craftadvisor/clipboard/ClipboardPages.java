package com.kuh.craftadvisor.clipboard;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits clipboard lines into pages. Create's clipboard screen fits about 15 one-line entries per
 * page and at most 51 pages; the server does not enforce either, so we keep within them ourselves.
 */
public final class ClipboardPages {
    public static final int ENTRIES_PER_PAGE = 15;
    public static final int MAX_PAGES = 51;

    private ClipboardPages() {
    }

    /**
     * Each page starts with {@code header} followed by up to {@code ENTRIES_PER_PAGE - 1} items.
     * At most {@code pageBudget} pages are produced; items that do not fit are dropped.
     */
    public static <T> List<List<T>> split(T header, List<T> items, int pageBudget) {
        int itemsPerPage = ENTRIES_PER_PAGE - 1;
        List<List<T>> pages = new ArrayList<>();
        for (int start = 0; start < items.size() && pages.size() < pageBudget; start += itemsPerPage) {
            List<T> page = new ArrayList<>();
            page.add(header);
            page.addAll(items.subList(start, Math.min(start + itemsPerPage, items.size())));
            pages.add(List.copyOf(page));
        }
        return List.copyOf(pages);
    }

    /** How many items made it onto the pages produced by {@link #split}. */
    public static int itemsWritten(List<? extends List<?>> pages) {
        return pages.stream().mapToInt(page -> page.size() - 1).sum();
    }
}

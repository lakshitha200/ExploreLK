package com.explorelk.destination.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The paging limits, in one place.
 *
 * <p>An uncapped page size is a free denial-of-service on a public endpoint:
 * {@code ?size=1000000} would materialise the whole table and every lazy
 * collection hanging off it. Values are clamped rather than rejected — a client
 * that asks for too much gets the maximum, not an error it has to handle.
 *
 * <p>Shared by the public and admin list endpoints so the two cannot drift; an
 * admin screen is still a screen, and paging a thousand drafts into it is the
 * same mistake.
 */
public final class Pagination {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private Pagination() {
    }

    /** {@code ?size=0} means "unset", not "empty page". */
    public static int clampSize(int size) {
        return size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    }

    /** Spring Data pages are zero-based; a negative page is a client bug, not a request. */
    public static int clampPage(int page) {
        return Math.max(page, 0);
    }

    public static PageRequest of(int page, int size, Sort sort) {
        return PageRequest.of(clampPage(page), clampSize(size), sort);
    }
}

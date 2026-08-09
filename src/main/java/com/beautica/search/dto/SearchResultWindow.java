package com.beautica.search.dto;

/**
 * The reachable-result window for offset-paginated public discovery, shared by
 * {@link MasterSearchRequest} and {@link SalonSearchRequest}.
 *
 * <h3>What this bounds, and why the per-field caps did not</h3>
 * {@code page} is capped at {@link #MAX_PAGE_INDEX} and {@code size} at
 * {@link #MAX_PAGE_SIZE}, but those caps <b>multiply</b>: {@code page=500&size=100}
 * asks Postgres to sort-and-discard a 50 000-row offset. That is now the worst shape
 * on these endpoints, because pages ≥ 5 are unconditionally uncached
 * ({@code @Cacheable(condition = "#pageable.pageNumber < 5")}) and an out-of-range page
 * costs two statements (the data query plus the first-page total probe). An
 * unauthenticated caller sweeping {@code ?page=5..500} therefore bought ~496 uncached
 * double-statement searches per rate-limit window.
 *
 * <p>So the bound is on the <b>product</b> — {@code offset + size <= }
 * {@link #MAX_RESULT_WINDOW} — not on either field alone. 10 000 is the industry
 * reference point: it is Elasticsearch's {@code index.max_result_window} default, and
 * GitHub and Google cap comparably. Bounding the product rather than lowering
 * {@code page}'s {@code @Max} keeps a small-page caller whole: at {@code size=20} the
 * full 500 pages stay reachable (page index 0…499 = exactly 10 000 rows), while
 * {@code size=100} is held to 100 pages.</p>
 *
 * <h3>400, never a silent clamp</h3>
 * An over-window request is rejected by {@code @AssertTrue} through the normal
 * validation envelope ({@code {"success": false, …, "errors": {…}}}). It is
 * deliberately NOT clamped: a clamp returns HTTP 200 with a page the caller did not
 * ask for, so a client paging forward sees a full page, believes more pages exist and
 * loops.
 *
 * <h3>Long-term answer</h3>
 * <b>Keyset (cursor) pagination is the correct fix for genuinely deep traversal</b> —
 * it is O(page size) at any depth instead of O(offset), and it does not skip or
 * duplicate rows when the underlying set changes between requests. It is not
 * implemented here: it changes the public response contract (an opaque cursor replaces
 * {@code page}) and the mobile client's paging model, so it belongs in its own phase.
 * This window is the bound that makes the offset form safe until then.
 */
public final class SearchResultWindow {

    /**
     * Maximum reachable result row, {@code offset + size}. Elasticsearch
     * {@code index.max_result_window} default.
     */
    public static final int MAX_RESULT_WINDOW = 10_000;

    /**
     * Server-side default page size when the caller supplies no {@code size}. Must
     * stay equal to the default {@code SearchController} applies — the window check
     * below assumes this value for a null {@code size}, so a drift would validate
     * against a page size the query never used.
     */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * Per-request page-size ceiling, mirroring
     * {@code spring.data.web.pageable.max-page-size} in {@code application.yml}.
     */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * Page-ordinal ceiling. Retained alongside the window check as an independent
     * bound so a hostile {@code size=1&page=999999999} is rejected on the field
     * itself, with a field-specific message, rather than only by the product check.
     */
    public static final int MAX_PAGE_INDEX = 500;

    private SearchResultWindow() {
        // constant holder
    }

    /**
     * {@code true} when the requested page lies inside {@link #MAX_RESULT_WINDOW}.
     *
     * <p>Null {@code page}/{@code size} mean "server default", so they can never be
     * out of window and return {@code true} via the defaults. Arithmetic is widened
     * to {@code long} so an out-of-range {@code page} that {@code @Max} will reject
     * anyway cannot overflow into a spuriously-valid product here — the two
     * constraints are evaluated independently and this one must be total on its own.</p>
     */
    public static boolean isWithinWindow(Integer page, Integer size) {
        long pageIndex = page == null ? 0L : page;
        long pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageIndex < 0 || pageSize <= 0) {
            // Rejected by @PositiveOrZero / @Positive on the field; not this check's concern.
            return true;
        }
        return pageIndex * pageSize + pageSize <= MAX_RESULT_WINDOW;
    }
}

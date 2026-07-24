package com.beautica.common.web;

import com.beautica.common.exception.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.util.HashSet;
import java.util.Set;

/**
 * Shared, per-endpoint {@code sort} whitelist for every paginated controller.
 *
 * <p><b>Why this exists (security, not ergonomics).</b> Spring Data appends a caller-supplied
 * {@link Sort} to the {@code ORDER BY} of the query behind the endpoint. Property names cannot be
 * bind parameters, so the caller's string is spliced into SQL/JPQL, and a <em>dotted association
 * path</em> resolves as valid JPQL against the query's root entity. On a {@code Booking} root,
 * {@code ?sort=client.passwordHash,asc} and {@code ?sort=client.email,asc} are both well-formed —
 * which turns an ordinary list endpoint into a <b>binary-search side channel over secrets the
 * caller may never read directly</b>: page through with alternating directions and the row order
 * leaks the relative ordering of password hashes or e-mail addresses.
 *
 * <p>This helper is the precise, per-endpoint contract: each call site states exactly which
 * properties it permits. It is deliberately paired with the global
 * {@link com.beautica.config.SortPathGuardConfig} backstop, which rejects <em>any</em> dotted path
 * on <em>every</em> endpoint — including ones added later that forget to call this helper. Neither
 * layer replaces the other: the backstop survives omission, this whitelist states intent.
 *
 * <p><b>Rejection is a 400 that never echoes the offending property.</b> Every guard throws
 * {@link BusinessException} with {@link HttpStatus#BAD_REQUEST}, which
 * {@code GlobalExceptionHandler#handleBusiness} genericises to {@code "Invalid request"} before it
 * reaches the client. The descriptive text below is therefore internal (debug log) only — it must
 * stay descriptive for operators, and it must never become client-visible, or the response itself
 * would confirm whether a probed property resolved against the entity graph.
 *
 * <p>Lifted verbatim from {@code BookingService#normalizeBookingSort} (Phase 26.3/26.8), which
 * pioneered this guard on {@code GET /bookings/me} and remains the reference implementation; the
 * message strings are preserved exactly so that suite's pinned assertions keep their meaning.
 */
public final class SortWhitelist {

    /**
     * Outer O(1) length bound applied before any per-order work, so a pathological
     * {@code ?sort=…&sort=…&sort=…&…} is rejected without allocating or walking anything.
     */
    private static final int MAX_SORT_ORDERS = 3;

    private SortWhitelist() {
        throw new AssertionError("Utility class");
    }

    /**
     * Validates {@code pageable}'s sort against {@code allowedProperties} and returns a Pageable
     * that is safe to hand to a repository.
     *
     * <ol>
     *   <li><b>Default when unsorted</b> — {@code defaultSort} is applied, so the query never
     *       falls back to DB-arbitrary order (which makes {@code OFFSET} paging non-deterministic).</li>
     *   <li><b>Count bound</b> — more than {@value #MAX_SORT_ORDERS} orders is a 400.</li>
     *   <li><b>Exact-match whitelist</b> — {@link Sort.Order#getProperty()} returns the FULL dotted
     *       path as a single string, so {@link Set#contains} rejects any multi-segment path
     *       outright; it never inspects only the first segment.</li>
     *   <li><b>No repeated property</b> — a repeat is never meaningful (SQL honours the first
     *       {@code ORDER BY} term per column) but each distinct sequence still compiles to its own
     *       {@code ORDER BY} text and therefore its own Postgres plan-cache entry.</li>
     *   <li><b>Mandatory tiebreaker</b> — appended last when supplied, so {@code OFFSET} paging
     *       over tied rows cannot duplicate or skip rows across pages.</li>
     * </ol>
     *
     * <p>Preserves {@link Pageable#unpaged()}: {@code getPageNumber()}/{@code getPageSize()} throw
     * on an {@code Unpaged} instance by design, so rebuilding via {@link PageRequest#of}
     * unconditionally would break legitimate unpaged callers.
     *
     * @param allowedProperties exact property names this endpoint permits; an empty set means the
     *                          endpoint is sort-locked and any caller-supplied sort is rejected
     * @param tiebreaker        appended after the caller's criteria; {@code null} when the
     *                          underlying query already imposes a unique total order
     */
    public static Pageable apply(Pageable pageable,
                                 Set<String> allowedProperties,
                                 Sort defaultSort,
                                 Sort tiebreaker) {
        Sort requestedSort = pageable.getSort();
        Sort effectiveSort = requestedSort.isUnsorted() ? defaultSort : requestedSort;

        if (effectiveSort.stream().count() > MAX_SORT_ORDERS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Too many sort properties");
        }

        Set<String> seenProperties = new HashSet<>();
        for (Sort.Order order : effectiveSort) {
            if (!allowedProperties.contains(order.getProperty())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "Unsupported sort property: " + order.getProperty());
            }
            if (!seenProperties.add(order.getProperty())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "Duplicate sort property: " + order.getProperty());
            }
        }

        Sort finalSort = tiebreaker == null ? effectiveSort : effectiveSort.and(tiebreaker);

        if (!pageable.isPaged()) {
            return Pageable.unpaged(finalSort);
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), finalSort);
    }

    /**
     * Strips the caller's sort entirely, for endpoints whose repository query hardcodes its own
     * {@code ORDER BY} and exposes no client-selectable ordering.
     *
     * <p>Rejecting rather than silently ignoring would be the stricter choice, but several of
     * these endpoints share the {@code sort} request-parameter name with a separate enum
     * {@code @RequestParam} (see {@code SalonReviewSort}); Spring binds {@code ?sort=HIGHEST}
     * twice — once as the enum and once as a bogus {@link Sort} property — so rejecting would
     * break the endpoint's own documented sort contract. Dropping the {@link Sort} while keeping
     * page number/size is what makes both readings coexist safely.
     */
    public static Pageable stripSort(Pageable pageable) {
        if (!pageable.isPaged()) {
            return Pageable.unpaged();
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }
}

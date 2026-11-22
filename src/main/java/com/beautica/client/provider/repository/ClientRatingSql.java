package com.beautica.client.provider.repository;

import static com.beautica.client.provider.repository.ClientListSql.MASTER_WHERE;
import static com.beautica.client.provider.repository.ClientListSql.SALON_MASTER_WHERE;
import static com.beautica.client.provider.repository.ClientListSql.SALON_WHERE;

/**
 * B526 — the viewer's own visible ratings of one registered client (CLI-59). Scope = B507's WHERE blocks verbatim;
 * {@code client_reviews.booking_id} is unique (uq_client_reviews_booking), so the join is an index read from the
 * scope's bookings. Only {@code cr.rating} is selected — never the comment, author or review id (REV-36).
 */
final class ClientRatingSql {

    private ClientRatingSql() {
    }

    /** The one join shared by the page and the count (the three scope WHEREs + FILTER follow it). */
    private static final String FROM = """
            FROM bookings b
              JOIN client_reviews cr ON cr.booking_id = b.id
              JOIN master_services ms ON ms.id = b.master_service_id
              JOIN service_definitions sd ON sd.id = ms.service_def_id
            """;

    private static final String PAGE_SELECT =
            "SELECT b.id AS bookingId, b.starts_at AS visitAt, sd.name AS serviceName, cr.rating AS stars\n" + FROM;

    private static final String FILTER = """
               AND cr.subject_client_id = :userId AND cr.moderation_status = 'VISIBLE'
            """;

    private static final String PAGE_TAIL = """
             ORDER BY b.starts_at DESC, b.id DESC
             LIMIT :limit OFFSET :offset
            """;

    private static final String COUNT_SELECT = "SELECT COUNT(*) " + FROM;

    static final String PAGE_SALON = PAGE_SELECT + SALON_WHERE + FILTER + PAGE_TAIL;
    static final String PAGE_SALON_MASTER = PAGE_SELECT + SALON_MASTER_WHERE + FILTER + PAGE_TAIL;
    static final String PAGE_MASTER = PAGE_SELECT + MASTER_WHERE + FILTER + PAGE_TAIL;

    // Existence check (D6 uniform 404): a booking of that identity in scope. The guest form mirrors the card's anchor
    // guard (ClientCardSql GUEST_CLOSE_ANCHOR): in scope, client_id IS NULL, phone present, not anonymised (GST-51).
    private static final String EXISTS_OPEN = "SELECT EXISTS (SELECT 1 FROM bookings b\n";
    private static final String EXISTS_USER_CLOSE = "   AND b.client_id = :userId)";
    private static final String EXISTS_GUEST_CLOSE = """
               AND b.id = :bookingId AND b.client_id IS NULL AND b.guest_phone IS NOT NULL
               AND b.guest_pii_anonymised_at IS NULL)""";

    static final String EXISTS_USER_SALON = EXISTS_OPEN + SALON_WHERE + EXISTS_USER_CLOSE;
    static final String EXISTS_USER_SALON_MASTER = EXISTS_OPEN + SALON_MASTER_WHERE + EXISTS_USER_CLOSE;
    static final String EXISTS_USER_MASTER = EXISTS_OPEN + MASTER_WHERE + EXISTS_USER_CLOSE;
    static final String EXISTS_GUEST_SALON = EXISTS_OPEN + SALON_WHERE + EXISTS_GUEST_CLOSE;
    // ?masterId= mode mirrors the card (ClientCardSql NARROW_TO_MASTER): the anchor is checked salon-wide, then a guest
    // booking with the anchor's phone must exist with that master; an anchor outside the salon is the uniform 404.
    static final String EXISTS_GUEST_SALON_MASTER = "WITH anchor AS (SELECT b.guest_phone FROM bookings b\n"
            + SALON_WHERE + """
               AND b.id = :bookingId AND b.client_id IS NULL AND b.guest_phone IS NOT NULL
               AND b.guest_pii_anonymised_at IS NULL)
            """ + EXISTS_OPEN + SALON_MASTER_WHERE + """
               AND b.client_id IS NULL AND b.guest_phone = (SELECT guest_phone FROM anchor)
               AND b.guest_pii_anonymised_at IS NULL AND b.client_detached_at IS NULL)""";
    static final String EXISTS_GUEST_MASTER = EXISTS_OPEN + MASTER_WHERE + EXISTS_GUEST_CLOSE;

    static final String COUNT_SALON = COUNT_SELECT + SALON_WHERE + FILTER;
    static final String COUNT_SALON_MASTER = COUNT_SELECT + SALON_MASTER_WHERE + FILTER;
    static final String COUNT_MASTER = COUNT_SELECT + MASTER_WHERE + FILTER;
}

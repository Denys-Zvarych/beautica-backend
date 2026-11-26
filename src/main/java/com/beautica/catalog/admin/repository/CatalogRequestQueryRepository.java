package com.beautica.catalog.admin.repository;

import com.beautica.catalog.admin.CatalogRequestKind;
import com.beautica.catalog.admin.CatalogRequestStatus;
import com.beautica.catalog.admin.dto.CatalogRequestDto;
import com.beautica.catalog.icon.CategoryIconUrls;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * B502 — the one read over BOTH request kinds (track D1): category requests ({@code platform_categories} rows with a
 * {@code requested_at}, i.e. created by a request — the seeded / ops-created APPROVED categories are not requests) and
 * {@code service_type_suggestion} rows, merged with {@code UNION ALL}, oldest first, paged in SQL.
 *
 * <p>{@code requester_label} is resolved in the same statement: the name of a salon the requester owns (primary
 * first), else their first + last name, else the name of the salon they work in. The SELECT list never reads
 * {@code users.email} or {@code users.phone}. Bounded by the caller's {@code Pageable} (global cap 100).
 */
@Repository
@RequiredArgsConstructor
public class CatalogRequestQueryRepository {

    /** Requester label when no user / name resolves (same placeholder as the request notification emails). */
    public static final String UNKNOWN_REQUESTER = "—";

    private static final String REQUESTS = """
            SELECT 'CATEGORY' AS kind, CAST(pc.id AS text) AS id, pc.name AS name, pc.display_name AS display_name,
                   CAST(NULL AS text) AS category_name, pc.initial_service_name AS initial_service_name,
                   CAST(NULL AS text) AS description, pc.requested_by_user_id AS requester_id,
                   pc.created_at AS created_at, pc.status AS status, pc.icon_key AS icon_key
              FROM platform_categories pc
             WHERE pc.status = :status AND pc.requested_at IS NOT NULL
            UNION ALL
            SELECT 'SERVICE_TYPE', CAST(s.id AS text), s.suggested_name, CAST(NULL AS text),
                   s.category_name, CAST(NULL AS text), s.description, s.requested_by_user_id,
                   s.created_at, s.status, CAST(NULL AS text)
              FROM service_type_suggestion s
             WHERE s.status = :status
            """;

    private static final String PAGE_SQL = """
            SELECT r.kind, r.id, r.name, r.display_name, r.category_name, r.initial_service_name, r.description,
                   COALESCE(
                       (SELECT sal.name FROM salons sal WHERE sal.owner_id = u.id
                         ORDER BY sal.is_primary DESC, sal.created_at, sal.id LIMIT 1),
                       NULLIF(btrim(concat_ws(' ', u.first_name, u.last_name)), ''),
                       (SELECT ws.name FROM salons ws WHERE ws.id = u.salon_id),
                       :unknown) AS requester_label,
                   r.created_at, r.status, r.icon_key
              FROM (%s) r
              LEFT JOIN users u ON u.id = r.requester_id
             ORDER BY r.created_at, r.kind, r.id
             LIMIT :limit OFFSET :offset
            """.formatted(REQUESTS);

    private static final String COUNT_SQL = """
            SELECT (SELECT COUNT(*) FROM platform_categories pc
                     WHERE pc.status = :status AND pc.requested_at IS NOT NULL)
                 + (SELECT COUNT(*) FROM service_type_suggestion s WHERE s.status = :status)
            """;

    /** B539 — {@code icon_key} → {@code iconUrl} at read time (track D8). */
    private final CategoryIconUrls iconUrls;

    @PersistenceContext
    private EntityManager entityManager;

    /** One page of requests in {@code status}, oldest first ({@code created_at}, then kind and id as tie-breakers). */
    public List<CatalogRequestDto> findPage(CatalogRequestStatus status, long offset, int limit) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(PAGE_SQL)
                .setParameter("status", status.name())
                .setParameter("unknown", UNKNOWN_REQUESTER)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
        return rows.stream().map(this::toDto).toList();
    }

    public long count(CatalogRequestStatus status) {
        Number total = (Number) entityManager.createNativeQuery(COUNT_SQL)
                .setParameter("status", status.name())
                .getSingleResult();
        return total.longValue();
    }

    private CatalogRequestDto toDto(Object[] row) {
        return new CatalogRequestDto(
                CatalogRequestKind.valueOf((String) row[0]),
                (String) row[1],
                (String) row[2],
                (String) row[3],
                (String) row[4],
                (String) row[5],
                (String) row[6],
                (String) row[7],
                toInstant(row[8]),
                CatalogRequestStatus.valueOf((String) row[9]),
                iconUrls.urlFor((String) row[10]));
    }

    /** Hibernate 6 maps {@code timestamptz} in a native result to {@code OffsetDateTime} or {@code Instant} by config. */
    private static Instant toInstant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case OffsetDateTime odt -> odt.toInstant();
            case Timestamp ts -> ts.toInstant();
            default -> throw new IllegalStateException("Unexpected created_at type: " + value.getClass().getName());
        };
    }
}

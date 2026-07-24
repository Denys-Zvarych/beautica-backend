package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 23.x — HTTP contract for the {@code serviceId}-PRESENT (availability-aware) mode of
 * {@code GET /api/v1/masters/{masterId}/working-days}, over the real Spring Security chain and a real
 * Testcontainers Postgres.
 *
 * <p>{@code MasterScheduleSecurityIT} already covers the {@code serviceId}-ABSENT (schedule-shape) mode's
 * HTTP surface (401, the 366-day range cap, the no-existence-oracle on masterId). This class pins the two
 * behaviours the {@code serviceId} parameter ADDS and that mode does not have:
 *
 * <ul>
 *   <li><b>the tighter 62-day span cap</b> (400 beyond it) — {@code SlotCalculationService#assertBookableSpan},
 *       which applies ONLY when {@code serviceId} is supplied; the schedule-shape mode keeps its 366-day
 *       allowance;</li>
 *   <li><b>404 parity</b> — an unknown, foreign (another master's) or inactive {@code masterServiceId} all
 *       return the SAME 404 body, so a caller holding a stale id gets no existence/ownership oracle.</li>
 * </ul>
 */
@DisplayName("MasterWorkingDaysServiceModeIT — working-days?serviceId= HTTP contract (62-day span cap + 404 parity)")
class MasterWorkingDaysServiceModeIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/masters";
    private static final BigDecimal PRICE = new BigDecimal("100.00");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    // ── auth + HTTP helpers (mirrors MasterScheduleSecurityIT) ──────────────────────────────

    private record Actor(UUID userId, String email, Role role) {
    }

    private Actor seedUser(Role role) {
        UUID userId = UUID.randomUUID();
        String email = role.name().toLowerCase() + "-" + userId + "@beautica.test";
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', ?, 'Fn', 'Ln', true, true)",
                userId, email, role.name());
        return new Actor(userId, email, role);
    }

    private HttpHeaders auth(Actor a) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtTokenProvider.generateAccessToken(a.userId(), a.email(), a.role()));
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> get(String path, Actor actor) {
        return restTemplate.exchange(BASE + path, HttpMethod.GET, new HttpEntity<>(auth(actor)), String.class);
    }

    private String workingDays(UUID masterId, LocalDate from, LocalDate to, UUID serviceId) {
        String path = "/" + masterId + "/working-days?from=" + from + "&to=" + to;
        return serviceId == null ? path : path + "&serviceId=" + serviceId;
    }

    // ── seeding ─────────────────────────────────────────────────────────────────────────────

    private UUID seedIndependentMaster() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', 'Ind', "
                        + "'Master', true, true)",
                userId, "ind-" + userId + "@beautica.test");
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, master_type, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    /** An {@code is_active}={@code active} master_services assignment on {@code masterId}; returns its id. */
    private UUID addServiceAssignment(UUID masterId, boolean active) {
        UUID ownerUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, "
                        + "service_type_id, base_duration_minutes, base_price, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) VALUES (?, 'INDEPENDENT_MASTER', ?, 'Svc', ?, "
                        + "60, ?, 0, true, NOW(), NOW())",
                serviceDefId, ownerUserId, resolveServiceTypeId(), PRICE);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId, active);
        return masterServiceId;
    }

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // Span cap — the 62-day ceiling applies ONLY in serviceId-PRESENT mode
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("62-day span cap (serviceId mode only)")
    class SpanCap {

        @Test
        @DisplayName("serviceId present, span 63 days between endpoints → 400")
        void should_return400_when_serviceIdSpanExceeds62Days() {
            UUID masterId = seedIndependentMaster();
            UUID serviceId = addServiceAssignment(masterId, true);
            Actor client = seedUser(Role.CLIENT);
            LocalDate from = LocalDate.now();
            LocalDate to = from.plusDays(63); // 63 > 62 between endpoints

            assertThat(get(workingDays(masterId, from, to, serviceId), client).getStatusCode())
                    .as("the serviceId-aware calendar window is capped at 62 days between endpoints")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("serviceId present, span exactly 62 days between endpoints → 200 (boundary accepted)")
        void should_return200_when_serviceIdSpanIsExactly62Days() {
            UUID masterId = seedIndependentMaster();
            UUID serviceId = addServiceAssignment(masterId, true);
            Actor client = seedUser(Role.CLIENT);
            LocalDate from = LocalDate.now();
            LocalDate to = from.plusDays(62);

            assertThat(get(workingDays(masterId, from, to, serviceId), client).getStatusCode())
                    .as("62 days between endpoints (two calendar months) is the accepted ceiling")
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("serviceId ABSENT, same 63-day span → 200 (the 62-day cap does not apply to schedule-shape mode)")
        void should_return200_when_scheduleShapeModeSpans63Days() {
            UUID masterId = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            LocalDate from = LocalDate.now();
            LocalDate to = from.plusDays(63);

            assertThat(get(workingDays(masterId, from, to, null), client).getStatusCode())
                    .as("without serviceId the endpoint keeps its wider 366-day allowance — the same "
                            + "63-day span that 400s in serviceId mode must still be 200 here")
                    .isEqualTo(HttpStatus.OK);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // 404 parity — unknown / foreign / inactive masterServiceId are indistinguishable (no oracle)
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("404 parity (no existence/ownership oracle)")
    class NotFoundParity {

        @Test
        @DisplayName("unknown, foreign-master and inactive masterServiceId all → 404 with the SAME body")
        void should_return404WithIdenticalBody_forUnknownForeignAndInactiveServiceId() throws Exception {
            UUID masterId = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            LocalDate day = LocalDate.now();

            // (a) unknown — no such assignment anywhere.
            UUID unknownServiceId = UUID.randomUUID();
            // (b) foreign — an ACTIVE assignment, but on a DIFFERENT master. The finder is master-scoped,
            //     so from masterId's perspective it is indistinguishable from unknown.
            UUID otherMaster = seedIndependentMaster();
            UUID foreignServiceId = addServiceAssignment(otherMaster, true);
            // (c) inactive — a soft-deleted assignment on THIS master.
            UUID inactiveServiceId = addServiceAssignment(masterId, false);

            ResponseEntity<String> unknown =
                    get(workingDays(masterId, day, day, unknownServiceId), client);
            ResponseEntity<String> foreign =
                    get(workingDays(masterId, day, day, foreignServiceId), client);
            ResponseEntity<String> inactive =
                    get(workingDays(masterId, day, day, inactiveServiceId), client);

            assertThat(List.of(
                    unknown.getStatusCode(), foreign.getStatusCode(), inactive.getStatusCode()))
                    .as("all three failure modes return 404 — never 400/403 that would distinguish them")
                    .containsOnly(HttpStatus.NOT_FOUND);

            // No oracle: the wire bodies must be byte-identical (a generic sentinel, no id/ownership echo).
            assertThat(foreign.getBody())
                    .as("foreign-master serviceId must be indistinguishable from unknown")
                    .isEqualTo(unknown.getBody());
            assertThat(inactive.getBody())
                    .as("inactive (soft-deleted) serviceId must be indistinguishable from unknown")
                    .isEqualTo(unknown.getBody());
            assertThat(objectMapper.readTree(unknown.getBody()).path("message").asText())
                    .as("the 404 carries the generic sentinel, not the internal 'masterService not found'")
                    .isEqualTo("Resource not found");
        }
    }
}

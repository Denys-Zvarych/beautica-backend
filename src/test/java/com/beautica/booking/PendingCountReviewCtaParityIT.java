package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.review.config.ClientRatingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The «Архів» pending badge ({@code toRateClient}) must count a booking IFF its detail
 * {@code providerCanReviewClient} CTA is lit — one rule ({@code ClientRatingEligibility}), two forms
 * (in-memory {@code isRateable} for the CTA, {@code rateable()} Specification for the count). Walks every
 * valid {@code booking_source} x client-identity shape x status, guest flag OFF here; the
 * {@link PendingCountReviewCtaParityGuestFlagIT} subclass re-runs it with the flag ON. Per row and per viewer:
 * insert, read the count delta and the CTA, record a mismatch when {@code counted != cta}; a COMPLETED row with a
 * lit CTA is then really rated (both must drop together) and the rating deleted (REV-45: both must return).
 * Every mismatching row is collected and reported at once (the label names viewer, source/identity and status),
 * so one red run lists the whole drift.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Pending badge toRateClient <=> providerCanReviewClient CTA, per row, guest rating flag OFF")
class PendingCountReviewCtaParityIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String ME_COUNT_URL = BOOKINGS_URL + "/me/pending-actions/count";
    private static final String CLIENT_REVIEWS_URL = "/api/v1/client-reviews";
    private static final String GUEST_PHONE = "+380501234567";
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);

    /** Every client-identity shape {@code chk_bookings_guest_fields} (V260) admits, per source. */
    enum Shape {
        APP_REGISTERED, LINK_GUEST, LINK_ANONYMISED, LINK_ATTACHED, LINK_DETACHED,
        STAFF_REGISTERED, STAFF_ATTACHED, STAFF_GUEST, STAFF_ANONYMISED,
        /** The prod-report shape (2026-11-27): STAFF walk-in linked to an account at create time. */
        STAFF_LINKED_AT_BOOKING
    }

    private static final List<String> STATUSES = List.of("COMPLETED", "CONFIRMED", "NOT_COMPLETED");

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ClientRatingProperties clientRatingProperties;

    private BookingTestFixtures fixtures;
    private int day;

    @BeforeEach
    void seedFixtures() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        day = 1;
    }

    /** The flag value this context must run with — the subclass flips it. */
    protected boolean expectedGuestFlag() {
        return false;
    }

    /** A provider viewer: the count endpoint it reads and its bearer token. */
    private record Viewer(String name, String countUrl, String token) {}

    /** Where the walked rows live: master, its service, the salon (null for an independent master). */
    private record Scope(UUID masterId, UUID serviceId, UUID salonId, UUID clientId) {}

    private record Walk(List<String> mismatches, List<String> lit, List<String> dark) {}

    @Test
    @DisplayName("independent master /me: every source x identity x status row is counted iff its detail CTA is "
            + "lit; rating clears and deleting the rating restores both")
    void should_countRowIffCtaLit_when_independentMasterWalksMatrix() throws Exception {
        assertThat(clientRatingProperties.guestEnabled()).as("premise: guest flag").isEqualTo(expectedGuestFlag());
        String email = "parity-m-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(email);
        Scope scope = new Scope(masterId, fixtures.createIndependentMasterService(masterId), null, newClient());
        Viewer master = new Viewer("indep", ME_COUNT_URL, fixtures.tokenFor(email));

        Walk walk = walk(scope, List.of(master));

        assertThat(walk.mismatches()).as("rows where counted != CTA").isEmpty();
        assertNonVacuous(walk, "indep");
    }

    @Test
    @DisplayName("salon endpoint, owner AND assigned admin: every salon-master row is counted iff that viewer's "
            + "detail CTA is lit; rating clears and deleting the rating restores both for both viewers")
    void should_countRowIffCtaLit_when_salonOwnerAndAdminWalkMatrix() throws Exception {
        assertThat(clientRatingProperties.guestEnabled()).as("premise: guest flag").isEqualTo(expectedGuestFlag());
        var salon = fixtures.createSalon("parity-o-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "parity-a-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        Scope scope = new Scope(salon.masterId(), fixtures.createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), newClient());
        String salonCountUrl = BOOKINGS_URL + "/salon/" + salon.salonId() + "/pending-actions/count";
        Viewer owner = new Viewer("owner", salonCountUrl, fixtures.tokenFor(salon.ownerEmail()));
        Viewer admin = new Viewer("admin", salonCountUrl, fixtures.tokenFor(adminEmail));

        Walk walk = walk(scope, List.of(owner, admin));

        assertThat(walk.mismatches()).as("rows where counted != CTA").isEmpty();
        assertNonVacuous(walk, "owner");
        assertNonVacuous(walk, "admin");
    }

    @Test
    @DisplayName("prod report 2026-11-27 regression: COMPLETED STAFF walk-in linked at booking "
            + "(client_link_reason=BOOKING), no review — counted exactly when the CTA is lit, and lit (REV-D04)")
    void should_countAndLightCta_when_completedStaffWalkInLinkedAtBooking() throws Exception {
        String email = "parity-prod-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(email);
        Scope scope = new Scope(masterId, fixtures.createIndependentMasterService(masterId), null, newClient());
        String token = fixtures.tokenFor(email);
        long before = count(ME_COUNT_URL, token);

        UUID id = insert(Shape.STAFF_LINKED_AT_BOOKING, "COMPLETED", scope);

        long counted = count(ME_COUNT_URL, token) - before;
        boolean cta = cta(id, token);
        assertThat(counted).as("badge delta vs CTA=%s", cta).isEqualTo(cta ? 1L : 0L);
        assertThat(cta).as("REV-D04: a COMPLETED walk-in with an attached account is rateable").isTrue();
    }

    @Test
    @DisplayName("deactivated independent master: its COMPLETED rateable row drops out of the badge AND the CTA "
            + "goes dark together")
    void should_excludeFromCountAndHideCta_when_independentMasterDeactivated() throws Exception {
        String email = "parity-off-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(email);
        Scope scope = new Scope(masterId, fixtures.createIndependentMasterService(masterId), null, newClient());
        String token = fixtures.tokenFor(email);
        UUID id = insert(Shape.APP_REGISTERED, "COMPLETED", scope);
        assertThat(count(ME_COUNT_URL, token)).as("premise: active master counts the row").isEqualTo(1L);
        assertThat(cta(id, token)).as("premise: active master sees the CTA").isTrue();

        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", masterId);

        assertThat(count(ME_COUNT_URL, token)).as("deactivated: toRateClient").isZero();
        assertThat(cta(id, token)).as("deactivated: providerCanReviewClient").isFalse();
    }

    private Walk walk(Scope scope, List<Viewer> viewers) throws Exception {
        Walk walk = new Walk(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (Shape shape : Shape.values()) {
            for (String status : STATUSES) {
                walkRow(scope, viewers, shape, status, walk);
            }
        }
        return walk;
    }

    private void walkRow(Scope scope, List<Viewer> viewers, Shape shape, String status, Walk walk) throws Exception {
        String row = shape + "/" + status;
        List<Long> before = new ArrayList<>();
        for (Viewer v : viewers) {
            before.add(count(v.countUrl(), v.token()));
        }
        UUID id = insert(shape, status, scope);
        boolean anyLit = false;
        for (int i = 0; i < viewers.size(); i++) {
            Viewer v = viewers.get(i);
            long counted = count(v.countUrl(), v.token()) - before.get(i);
            boolean cta = cta(id, v.token());
            String label = v.name() + ":" + row;
            if (counted != (cta ? 1L : 0L)) {
                walk.mismatches().add(label + " counted=" + counted + " cta=" + cta);
            }
            (cta ? walk.lit() : walk.dark()).add(label);
            anyLit |= cta;
        }
        if (!anyLit) {
            return;
        }
        Viewer rater = viewers.get(0);
        ResponseEntity<String> rated = rate(id, rater.token());
        if (rated.getStatusCode() != HttpStatus.CREATED) {
            walk.mismatches().add(rater.name() + ":" + row + " lit CTA but rate=" + rated.getStatusCode());
            return;
        }
        checkAll(viewers, before, id, row, "after rating", 0L, false, walk);
        String reviewId = objectMapper.readTree(rated.getBody()).path("data").path("id").asText();
        HttpStatus deleted = delete(reviewId, rater.token());
        if (deleted != HttpStatus.NO_CONTENT) {
            walk.mismatches().add(rater.name() + ":" + row + " delete own rating=" + deleted);
            return;
        }
        checkAll(viewers, before, id, row, "after deleting the rating (REV-45)", 1L, true, walk);
    }

    /** Every viewer's count delta vs {@code before} and CTA must equal the expected pair; mismatches recorded. */
    private void checkAll(List<Viewer> viewers, List<Long> before, UUID id, String row, String phase,
                          long expectedDelta, boolean expectedCta, Walk walk) throws Exception {
        for (int i = 0; i < viewers.size(); i++) {
            Viewer v = viewers.get(i);
            long delta = count(v.countUrl(), v.token()) - before.get(i);
            boolean cta = cta(id, v.token());
            if (delta != expectedDelta || cta != expectedCta) {
                walk.mismatches().add(v.name() + ":" + row + " " + phase + " counted=" + delta + " cta=" + cta);
            }
        }
    }

    private void assertNonVacuous(Walk walk, String viewer) {
        assertThat(walk.lit()).as("non-vacuous (%s): registered + attached COMPLETED rows are lit", viewer)
                .contains(viewer + ":APP_REGISTERED/COMPLETED", viewer + ":STAFF_REGISTERED/COMPLETED",
                        viewer + ":LINK_ATTACHED/COMPLETED", viewer + ":STAFF_LINKED_AT_BOOKING/COMPLETED");
        assertThat(walk.dark()).as("non-vacuous (%s): non-COMPLETED and identity-less rows stay dark", viewer)
                .contains(viewer + ":APP_REGISTERED/CONFIRMED", viewer + ":APP_REGISTERED/NOT_COMPLETED",
                        viewer + ":LINK_DETACHED/COMPLETED", viewer + ":STAFF_ANONYMISED/COMPLETED",
                        viewer + ":LINK_ANONYMISED/COMPLETED");
        List<String> guestRows = List.of(viewer + ":STAFF_GUEST/COMPLETED", viewer + ":LINK_GUEST/COMPLETED");
        assertThat(expectedGuestFlag() ? walk.lit() : walk.dark())
                .as("guest + walk-in with a live phone follow the flag (%s)", viewer).containsAll(guestRows);
    }

    private UUID newClient() {
        return fixtures.createUser("parity-c-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
    }

    private UUID insert(Shape shape, String status, Scope scope) {
        UUID id = UUID.randomUUID();
        OffsetDateTime startsAt = NOW.minusDays(day++).minusHours(5);
        String source = shape.name().substring(0, shape.name().indexOf('_'));
        UUID client = null;
        String name = null;
        String surname = null;
        String phone = null;
        OffsetDateTime anonymisedAt = null;
        OffsetDateTime detachedAt = null;
        String linkReason = null;
        String cancelHash = null;
        switch (shape) {
            case APP_REGISTERED, STAFF_REGISTERED -> client = scope.clientId();
            case LINK_ATTACHED, STAFF_ATTACHED -> {
                client = scope.clientId();
                name = "Attached";
                linkReason = "PHONE_VERIFIED";
            }
            case STAFF_LINKED_AT_BOOKING -> {
                client = scope.clientId();
                name = "Walk";
                surname = "In";
                linkReason = "BOOKING";
            }
            case LINK_GUEST -> {
                name = "Guest";
                phone = GUEST_PHONE;
                cancelHash = "COMPLETED".equals(status) || "NOT_COMPLETED".equals(status) ? null : randomHash();
            }
            case STAFF_GUEST -> {
                name = "Walk";
                surname = "In";
                phone = GUEST_PHONE;
            }
            case LINK_ANONYMISED, STAFF_ANONYMISED -> {
                name = "Anon";
                anonymisedAt = NOW;
            }
            case LINK_DETACHED -> {
                name = "Detached";
                detachedAt = NOW;
            }
        }
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, starts_at, "
                        + "ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, guest_pii_anonymised_at, "
                        + "client_detached_at, client_link_reason, client_linked_at, cancel_token_hash, created_at, "
                        + "updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 500.00, 60, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                id, client, scope.masterId(), scope.serviceId(), scope.salonId(), status, startsAt,
                startsAt.plusHours(1), source, name, surname, phone, anonymisedAt, detachedAt, linkReason,
                linkReason == null ? null : NOW, cancelHash);
        return id;
    }

    private static String randomHash() {
        byte[] bytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private long count(String url, String token) throws Exception {
        return data(url, token).path("toRateClient").asLong();
    }

    private boolean cta(UUID bookingId, String token) throws Exception {
        JsonNode flag = data(BOOKINGS_URL + "/" + bookingId, token).path("providerCanReviewClient");
        assertThat(flag.isBoolean()).as("detail exposes providerCanReviewClient").isTrue();
        return flag.asBoolean();
    }

    private JsonNode data(String url, String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).as("GET %s body=%s", url, resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private ResponseEntity<String> rate(UUID bookingId, String token) throws Exception {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = objectMapper.writeValueAsString(Map.of("bookingId", bookingId.toString(), "rating", 5));
        return restTemplate.exchange(
                CLIENT_REVIEWS_URL, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private HttpStatus delete(String reviewId, String token) {
        ResponseEntity<String> resp = restTemplate.exchange(CLIENT_REVIEWS_URL + "/" + reviewId, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        return HttpStatus.valueOf(resp.getStatusCode().value());
    }
}

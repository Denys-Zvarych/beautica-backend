package com.beautica.review.invite;

import com.beautica.common.security.PhoneHasher;
import com.beautica.review.invite.service.ReviewInviteTokenHasher;
import com.beautica.review.service.RatingRecalculationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 401 audit-fix cycle 1 (perf MEDIUM) — a multi-item guest submit writes N reviews in ONE transaction but
 * {@code ReviewService#createGuestLinkReviews} publishes ONE {@code ReviewCreatedEvent} per distinct provider, so the
 * commit costs exactly one master and one salon rating recalculation (through the real AFTER_COMMIT
 * {@code ReviewEventListener}), and the aggregates still count every review of the submit.
 *
 * <p>Its own Spring context on purpose: the {@link MockitoSpyBean} is a bean override, so sharing the review-invite
 * ITs' context is impossible. Its tests share that one context, so the extra boot is paid once.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.booking.review-invite.enabled=true",
        "app.booking.review-invite.min-policy-version=2026-10-20"
})
@DisplayName("Guest review submit — rating recalculation coalesced per transaction (phase 401 audit-fix)")
class GuestReviewRatingCoalescingIT extends AbstractReviewInviteIT {

    private static final String TOKEN = "CoalesceThreeItems_0001";

    @MockitoSpyBean
    private RatingRecalculationService ratingRecalculationService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PhoneHasher phoneHasher;

    private void sentVisitInvite(UUID appointmentId) {
        Instant now = Instant.now();
        jdbcTemplate.update("INSERT INTO review_invites (id, appointment_id, phone_hash, token_hash, status, send_after, "
                        + "sent_at, expires_at, created_at) VALUES (?, ?, ?, ?, 'SENT', ?, ?, ?, ?)",
                UUID.randomUUID(), appointmentId, phoneHasher.hash(GUEST_PHONE).hash(),
                ReviewInviteTokenHasher.hash(TOKEN), Timestamp.from(now.minus(Duration.ofHours(1))),
                Timestamp.from(now.minus(Duration.ofHours(1))), Timestamp.from(now.plus(Duration.ofDays(14))),
                Timestamp.from(now.minus(Duration.ofHours(1))));
    }

    private ResponseEntity<String> submit(String itemsJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", "198.18.250.1");
        return restTemplate.exchange("/api/v1/public/review-invites/" + TOKEN + "/reviews", HttpMethod.POST,
                new HttpEntity<>("{\"turnstileToken\":\"t\",\"items\":" + itemsJson + "}", headers), String.class);
    }

    private static String item(UUID bookingId, int rating) {
        return "{\"bookingId\":\"" + bookingId + "\",\"rating\":" + rating + "}";
    }

    @Test
    @DisplayName("3-item salon submit → exactly 1 master + 1 salon recalculation, both aggregates count all 3 reviews")
    void should_recalculateOncePerProvider_when_threeItemsSubmittedInOneTransaction() {
        UUID salonId = affiliateMasterWithSalon();
        UUID appointmentId = fixtures.linkVisit("COMPLETED", GUEST_PHONE, POLICY);
        UUID first = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE, 8);
        UUID second = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE, 6);
        UUID third = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE, 4);
        attachToSalon(salonId, first, second, third);
        sentVisitInvite(appointmentId);
        clearInvocations(ratingRecalculationService);

        ResponseEntity<String> created = submit("[" + item(first, 5) + "," + item(second, 4) + "," + item(third, 3) + "]");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(ratingRecalculationService, times(1)).recalculateMasterRating(any());
        verify(ratingRecalculationService, times(1)).recalculateMasterRating(masterId);
        verify(ratingRecalculationService, times(1)).recalculateSalonRating(any());
        verify(ratingRecalculationService, times(1)).recalculateSalonRating(salonId);
        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM masters WHERE id = ?", Integer.class,
                masterId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT avg_rating FROM masters WHERE id = ?", BigDecimal.class,
                masterId)).isEqualByComparingTo("4");
        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM salons WHERE id = ?", Integer.class,
                salonId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT avg_rating FROM salons WHERE id = ?", BigDecimal.class,
                salonId)).isEqualByComparingTo("4");
    }

    /**
     * V190's {@code trg_bookings_single_master_per_appointment} keeps one master per visit today, so a K-master salon
     * visit is unreachable through the booking flow; {@code ReviewService#createGuestLinkReviews} still coalesces the
     * salon defensively (phase 401 audit-fix cycle 2). The trigger is suspended for this ONE seed insert only and
     * re-enabled in {@code finally}, so no other test ever runs without it.
     */
    private UUID secondMasterItem(UUID appointmentId, UUID secondMasterId, UUID secondServiceId) {
        jdbcTemplate.execute("ALTER TABLE bookings DISABLE TRIGGER trg_bookings_single_master_per_appointment");
        try {
            return fixtures.visitItem(appointmentId, secondMasterId, secondServiceId, "LINK", "COMPLETED",
                    GUEST_PHONE, 6);
        } finally {
            jdbcTemplate.execute("ALTER TABLE bookings ENABLE TRIGGER trg_bookings_single_master_per_appointment");
        }
    }

    @Test
    @DisplayName("2-master salon visit → exactly 1 salon + 2 master recalculations, every aggregate correct")
    void should_recalculateSalonOnce_when_salonVisitServedByTwoMasters() {
        UUID salonId = affiliateMasterWithSalon();
        UUID secondMasterId = fixtures.independentMaster();
        UUID secondServiceId = fixtures.masterService(secondMasterId);
        jdbcTemplate.update("UPDATE masters SET salon_id = ?, master_type = 'SALON_MASTER' WHERE id = ?", salonId,
                secondMasterId);
        UUID appointmentId = fixtures.linkVisit("COMPLETED", GUEST_PHONE, POLICY);
        UUID first = fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "COMPLETED", GUEST_PHONE, 8);
        UUID second = secondMasterItem(appointmentId, secondMasterId, secondServiceId);
        attachToSalon(salonId, first, second);
        sentVisitInvite(appointmentId);
        clearInvocations(ratingRecalculationService);

        ResponseEntity<String> created = submit("[" + item(first, 5) + "," + item(second, 2) + "]");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(ratingRecalculationService, times(2)).recalculateMasterRating(any());
        verify(ratingRecalculationService, times(1)).recalculateMasterRating(masterId);
        verify(ratingRecalculationService, times(1)).recalculateMasterRating(secondMasterId);
        verify(ratingRecalculationService, times(1)).recalculateSalonRating(any());
        verify(ratingRecalculationService, times(1)).recalculateSalonRating(salonId);
        assertThat(jdbcTemplate.queryForObject("SELECT avg_rating FROM masters WHERE id = ?", BigDecimal.class,
                masterId)).isEqualByComparingTo("5");
        assertThat(jdbcTemplate.queryForObject("SELECT avg_rating FROM masters WHERE id = ?", BigDecimal.class,
                secondMasterId)).isEqualByComparingTo("2");
        assertThat(jdbcTemplate.queryForObject("SELECT review_count FROM salons WHERE id = ?", Integer.class,
                salonId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT avg_rating FROM salons WHERE id = ?", BigDecimal.class,
                salonId)).isEqualByComparingTo("3.5");
    }
}

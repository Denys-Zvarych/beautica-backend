package com.beautica.review.invite;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.review.support.GuestReviewFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Phase 400 — shared rig for the review-invite ITs: a Turbosms WireMock (started once per JVM, never stopped between
 * classes), a root-logger capture for the "never logged" claims, and a guest LINK visit seeded through
 * {@link GuestReviewFixtures}. Never mocks {@code SmsService}: the real gated bean is part of what is under test.
 */
abstract class AbstractReviewInviteIT extends AbstractIntegrationTest {

    protected static final String TURBOSMS_PATH = "/message/send.json";
    protected static final String GUEST_PHONE = "+380502223344";
    protected static final String GUEST_SUBSCRIBER_DIGITS = "502223344";
    protected static final String POLICY = "2026-10-20";
    /** 12:00 Kyiv (summer) — outside the quiet window. */
    protected static final Instant MIDDAY = Instant.parse("2026-06-10T09:00:00Z");
    /** 23:00 Kyiv (summer) — inside the quiet window. */
    protected static final Instant NIGHT = Instant.parse("2026-06-10T20:00:00Z");

    protected static final WireMockServer TURBOSMS = new WireMockServer(options().dynamicPort());

    static {
        TURBOSMS.start();
        Runtime.getRuntime().addShutdownHook(new Thread(TURBOSMS::stop));
    }

    @DynamicPropertySource
    static void turbosmsEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.sms.turbosms.base-url", () -> TURBOSMS.baseUrl() + TURBOSMS_PATH);
        // Non-blank on purpose: a blank token short-circuits TurbosmsService#send before the HTTP call, so
        // "zero provider calls" would hold for the wrong reason.
        registry.add("app.sms.turbosms.token", () -> "wiremock-turbosms-token");
    }

    @Autowired
    protected ObjectMapper objectMapper;

    protected GuestReviewFixtures fixtures;
    protected UUID masterId;
    protected UUID masterUserId;
    protected UUID masterServiceId;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger rootLogger;

    @BeforeEach
    void setUpReviewInviteRig() {
        TURBOSMS.resetAll();
        stubTurbosms(200, "{\"response_status\":\"OK\"}");
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);

        fixtures = new GuestReviewFixtures(jdbcTemplate);
        masterId = fixtures.independentMaster();
        masterUserId = fixtures.masterUserId(masterId);
        masterServiceId = fixtures.masterService(masterId);
    }

    @AfterEach
    void detachLogAppender() {
        rootLogger.detachAppender(logAppender);
        logAppender.stop();
        SecurityContextHolder.clearContext();
    }

    /**
     * Service-level completion calls read the actor's role from the {@code SecurityContext}
     * ({@code AuthorizationService#enforceCanManageAppointment}); the HTTP filter that normally sets it is bypassed here.
     */
    protected void authenticateAsMaster() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "master@beautica.test", null, List.of(new SimpleGrantedAuthority("ROLE_" + Role.INDEPENDENT_MASTER)));
        auth.setDetails(masterUserId);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    protected static void stubTurbosms(int status, String body) {
        TURBOSMS.stubFor(post(urlEqualTo(TURBOSMS_PATH))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    protected List<LoggedRequest> turbosmsRequests() {
        return TURBOSMS.findAll(postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    protected JsonNode payload(LoggedRequest request) throws Exception {
        return objectMapper.readTree(request.getBodyAsString());
    }

    /** Every captured log line, formatted, plus any throwable message. */
    protected List<String> logLines() {
        return logAppender.list.stream()
                .map(e -> e.getFormattedMessage()
                        + (e.getThrowableProxy() == null ? "" : " " + e.getThrowableProxy().getMessage()))
                .toList();
    }

    /**
     * Phase 401 audit-fix — moves {@link #masterId} into a new active salon owned by a separate SALON_OWNER account
     * (so owner ≠ performing master); returns the salon id. Bookings seeded afterwards still carry
     * {@code salon_id = NULL}; {@link #attachToSalon} stamps them.
     */
    protected UUID affiliateMasterWithSalon() {
        UUID ownerId = fixtures.user("SALON_OWNER", "Олег", "Власник");
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                + "VALUES (?, ?, 'Лаванда', true, NOW(), NOW(), ?)", salonId, ownerId, testCityId());
        jdbcTemplate.update("UPDATE masters SET salon_id = ?, master_type = 'SALON_MASTER' WHERE id = ?", salonId,
                masterId);
        return salonId;
    }

    protected UUID salonOwnerId(UUID salonId) {
        return jdbcTemplate.queryForObject("SELECT owner_id FROM salons WHERE id = ?", UUID.class, salonId);
    }

    /** Stamps {@code salon_id} on the given bookings (a salon-performed visit). */
    protected void attachToSalon(UUID salonId, UUID... bookingIds) {
        for (UUID bookingId : bookingIds) {
            jdbcTemplate.update("UPDATE bookings SET salon_id = ? WHERE id = ?", salonId, bookingId);
        }
    }

    /** A COMPLETED guest LINK visit with one COMPLETED, unreviewed item. */
    protected UUID completedLinkVisit(String phone, String policyVersion) {
        UUID appointmentId = fixtures.linkVisit("COMPLETED", phone, policyVersion);
        fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "COMPLETED", phone, 5);
        return appointmentId;
    }

    /** A {@code SCHEDULED} invite row for {@code appointmentId} with the given {@code send_after}. */
    protected UUID scheduledInvite(UUID appointmentId, byte[] phoneHash, Instant sendAfter) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO review_invites (id, appointment_id, phone_hash, status, send_after, created_at) "
                + "VALUES (?, ?, ?, 'SCHEDULED', ?, ?)", id, appointmentId, phoneHash,
                java.sql.Timestamp.from(sendAfter), java.sql.Timestamp.from(sendAfter));
        return id;
    }

    /** Phase 400 D7 — a {@code SCHEDULED} booking-keyed invite row for {@code bookingId}. */
    protected UUID scheduledBookingInvite(UUID bookingId, byte[] phoneHash, Instant sendAfter) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO review_invites (id, booking_id, phone_hash, status, send_after, created_at) "
                + "VALUES (?, ?, ?, 'SCHEDULED', ?, ?)", id, bookingId, phoneHash,
                java.sql.Timestamp.from(sendAfter), java.sql.Timestamp.from(sendAfter));
        return id;
    }

    protected java.util.Map<String, Object> inviteRow(UUID inviteId) {
        return jdbcTemplate.queryForMap("SELECT * FROM review_invites WHERE id = ?", inviteId);
    }

    protected int inviteCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM review_invites", Integer.class);
    }
}

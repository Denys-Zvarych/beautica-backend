package com.beautica.moderation;

import com.beautica.booking.service.BookingSmsDispatcher;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.inapp.push.InAppPushDispatcher;
import com.beautica.notification.inapp.push.InAppPushDispatcher.PushPlan;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.AopTestUtils;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 398 (user decision 2026-10-28) — QA edge cases of the block → cancel-future-bookings cascade, real HTTP +
 * real Postgres: a SALON_ADMIN actor across salon masters incl. the owner-as-master, atomicity (a failing cascade
 * leaves neither a block nor a notice), the freed slot is bookable by someone else, one guest SMS per visit with
 * the unchanged rebook text, and the app client's push copy never mentions a block. {@code cancelledBookings}
 * counts visits (orchestrator decision 2026-10-28); the performing masters get the ordinary provider-decline notice.
 */
@DisplayName("Block cascade — admin actor, rollback, freed slot, guest SMS, neutral push (phase 398 QA IT)")
class BlockCascadeEdgeCasesIT extends AbstractBookingBlockIT {

    private static final String GUEST_PHONE = "+380671234521";

    @MockBean
    private BookingSmsDispatcher bookingSmsDispatcher;

    @SpyBean
    private InAppNotificationService inAppNotificationService;

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Autowired
    private InAppPushDispatcher inAppPushDispatcher;

    @Test
    @DisplayName("SALON_ADMIN blocks a client: future bookings with salon master A, the owner-as-master and a "
            + "whole visit with master B are all DECLINED by the provider — no comment, client notified per visit")
    void should_declineAcrossSalonMasters_when_salonAdminBlocksClient() throws Exception {
        SalonFixture salon = createSalon();
        UUID serviceA = makeSalonMasterBookable(salon.salonId(), salon.masterId());
        UUID ownerMasterId = insertOwnerAsMaster(salon);
        UUID ownerService = makeSalonMasterBookable(salon.salonId(), ownerMasterId);
        UUID masterB = BookableMasterSeeder.addBookableSalonMaster(jdbcTemplate, salon.salonId());
        UUID firstB = jdbcTemplate.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ?", UUID.class, masterB);
        UUID secondB = assignmentOf(BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon.salonId(), masterB));
        String adminEmail = unique("admin");
        createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        String clientEmail = unique("client");
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        String clientToken = tokenFor(clientEmail);
        UUID singleA = createdId(appBook(clientToken, salon.masterId(), serviceA, slot(3, 10)));
        UUID ownerBooking = createdId(appBook(clientToken, ownerMasterId, ownerService, slot(3, 12)));
        UUID visitB = createdId(appVisit(clientToken, masterB, List.of(firstB, secondB), slot(4, 10)));
        UUID source = insertCompletedBooking(clientId, salon);

        ResponseEntity<String> resp = postBlock(tokenFor(adminEmail), source);

        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(resp).path("cancelledBookings").asInt())
                .as("counts visits: master A's booking + the owner's booking + master B's 2-item visit")
                .isEqualTo(3);
        assertDeclinedByProvider(singleA);
        assertDeclinedByProvider(ownerBooking);
        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM bookings WHERE appointment_id = ?", String.class, visitB))
                .as("every item of master B's visit").containsExactly("DECLINED", "DECLINED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, visitB)).isEqualTo("DECLINED");
        assertThat(statusOf(source)).isEqualTo("COMPLETED");
        assertThat(inAppRows(clientId, "BOOKING_DECLINED"))
                .as("one in-app notice per visit: master A + owner-as-master + master B's visit")
                .isEqualTo(3);
        assertThat(inAppRows(userOfMaster(salon.masterId()), "BOOKING_DECLINED")).as("master A").isEqualTo(1);
        assertThat(inAppRows(userOfMaster(ownerMasterId), "BOOKING_DECLINED"))
                .as("the owner performs a booking and is not the actor (the admin is) — notified like a master")
                .isEqualTo(1);
        assertThat(inAppRows(userOfMaster(masterB), "BOOKING_DECLINED")).as("master B, once per visit").isEqualTo(1);
    }

    @Test
    @DisplayName("the cascade throws mid-way (client notice insert fails): POST /blocks fails and the whole "
            + "transaction rolls back — no block row, no outbox row, the future booking stays CONFIRMED")
    void should_persistNoBlock_when_cascadeDeclineThrows() throws Exception {
        SalonFixture salon = createSalon();
        UUID service = makeSalonMasterBookable(salon.salonId(), salon.masterId());
        String clientEmail = unique("client");
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        UUID future = createdId(appBook(tokenFor(clientEmail), salon.masterId(), service, slot(3, 10)));
        UUID source = insertCompletedBooking(clientId, salon);
        int blocksBefore = countBlocks();
        int outboxBefore = outboxRowsFor(future);
        // Why AopTestUtils.getTargetObject: InAppNotificationService carries @Transactional(MANDATORY), so the
        // injected @SpyBean field is the Spring AOP (CGLIB) transaction proxy WRAPPING the Mockito spy, not the spy
        // itself. Mockito's doThrow().when(x) must receive the spy instance — the proxy is not a Mockito mock, so
        // stubbing it directly is rejected (NotAMockException) instead of arming the throw.
        // Unwrapping to the target keeps the real transaction advice in the call path, so the throw happens INSIDE
        // the block's transaction exactly as a real insert failure would.
        doThrow(new IllegalStateException("simulated in-app insert failure"))
                .when(AopTestUtils.<InAppNotificationService>getTargetObject(inAppNotificationService))
                .notifyBookingEvent(eq(InAppNotificationType.BOOKING_DECLINED), any(), any());

        ResponseEntity<String> resp = postBlock(tokenFor(salon.ownerEmail()), source);

        assertThat(resp.getStatusCode().is5xxServerError()).as(resp.getBody()).isTrue();
        assertThat(countBlocks()).as("no block row survives the rollback").isEqualTo(blocksBefore);
        assertThat(outboxRowsFor(future))
                .as("the STATUS_CHANGED row enqueued before the failure is rolled back too")
                .isEqualTo(outboxBefore);
        assertThat(statusOf(future)).isEqualTo("CONFIRMED");
        assertThat(inAppRows(clientId, "BOOKING_DECLINED")).isZero();
    }

    @Test
    @DisplayName("the cancelled booking's slot is free again: it reappears in GET /slots and another client books "
            + "it (201), where before the block the same request was refused")
    void should_freeSlotForAnotherClient_when_blockDeclinesFutureBooking() throws Exception {
        IndependentMaster master = createIndependentMaster(unique("indep"));
        UUID service = makeIndependentBookable(master.masterId());
        String clientEmail = unique("client");
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        String otherEmail = unique("client-other");
        createUser(otherEmail, "CLIENT", null);
        String otherToken = tokenFor(otherEmail);
        ZonedDateTime startsAt = slot(5, 11);
        UUID future = createdId(appBook(tokenFor(clientEmail), master.masterId(), service, startsAt));
        assertThat(appBook(otherToken, master.masterId(), service, startsAt).getStatusCode())
                .as("precondition: the slot is taken before the block").isNotEqualTo(HttpStatus.CREATED);
        assertThat(slotStarts(otherToken, master.masterId(), service, startsAt))
                .as("precondition: GET /slots (cache warmed) hides the taken start").doesNotContain(startsAt.toInstant());
        UUID source = insertIndependentBooking(clientId, master.masterId());

        ResponseEntity<String> blockResp = postBlock(tokenFor(emailOf(master.userId())), source);

        assertThat(blockResp.getStatusCode()).as(blockResp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertDeclinedByProvider(future);
        assertThat(slotStarts(otherToken, master.masterId(), service, startsAt))
                .as("slot cache evicted after commit: the start is offered again").contains(startsAt.toInstant());
        assertCreated(appBook(otherToken, master.masterId(), service, startsAt));
    }

    @Test
    @DisplayName("guest block on a 2-service LINK visit: exactly ONE provider-cancel SMS for the visit, unchanged "
            + "text incl. the «Записатися знову» rebook link, nothing about a block")
    void should_sendOneSmsPerVisitWithRebookLink_when_salonBlocksGuestWithVisit() throws Exception {
        SalonFixture salon = createSalon();
        UUID first = makeSalonMasterBookable(salon.salonId(), salon.masterId());
        UUID second = assignmentOf(
                BookableMasterSeeder.assignNewSalonService(jdbcTemplate, salon.salonId(), salon.masterId()));
        String salonSlug = setSalonSlug(salon.salonId());
        ResponseEntity<String> created = guestBookSalon(guestTokenProvider.generate(GUEST_PHONE), salonSlug,
                salon.masterId(), List.of(first, second), slot(3, 10));
        assertCreated(created);
        UUID anyItem = UUID.fromString(readTree(created).path("bookingId").asText());
        UUID visitId = jdbcTemplate.queryForObject(
                "SELECT appointment_id FROM bookings WHERE id = ?", UUID.class, anyItem);
        UUID source = insertGuestBooking(salon, "LINK", GUEST_PHONE);

        ResponseEntity<String> resp = postBlock(tokenFor(salon.ownerEmail()), source);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM bookings WHERE appointment_id = ?", String.class, visitId))
                .containsExactly("DECLINED", "DECLINED");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(bookingSmsDispatcher, times(1)).dispatchOrThrow(
                eq(BookingSmsDispatcher.Kind.GUEST_PROVIDER_CANCELLATION), eq(GUEST_PHONE), text.capture());
        assertThat(text.getValue())
                .startsWith("Beautica: Ваш запис ")
                .contains("скасовано. Записатися знову: ")
                .endsWith("/book/s/" + salonSlug)
                .doesNotContainIgnoringCase("блок");
    }

    @Test
    @DisplayName("app client: the push copy and the GET /notifications feed for the cascade decline are the "
            + "neutral «Ваш запис скасовано» notice — no «блок» anywhere")
    void should_notMentionBlockInClientNotification_when_blockDeclinesAppBooking() throws Exception {
        IndependentMaster master = createIndependentMaster(unique("indep"));
        UUID service = makeIndependentBookable(master.masterId());
        String clientEmail = unique("client");
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        String clientToken = tokenFor(clientEmail);
        createdId(appBook(clientToken, master.masterId(), service, slot(3, 10)));
        jdbcTemplate.update("INSERT INTO device_tokens (user_id, token, platform) VALUES (?, ?, 'ANDROID')",
                clientId, "fcm-" + UUID.randomUUID());
        UUID source = insertIndependentBooking(clientId, master.masterId());

        ResponseEntity<String> resp = postBlock(tokenFor(emailOf(master.userId())), source);

        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.CREATED);
        List<UUID> declinedRows = jdbcTemplate.queryForList(
                "SELECT id FROM in_app_notification WHERE recipient_user_id = ? AND type = 'BOOKING_DECLINED'",
                UUID.class, clientId);
        assertThat(declinedRows).hasSize(1);
        Map<UUID, PushPlan> plans = inAppPushDispatcher.prepare(declinedRows);
        assertThat(plans).hasSize(1);
        PushPlan plan = plans.values().iterator().next();
        assertThat(plan.title()).isEqualTo("Ваш запис скасовано");
        assertThat(plan.title() + " " + plan.body()).doesNotContainIgnoringCase("блок");
        ResponseEntity<String> feed = exchange(HttpMethod.GET, "/api/v1/notifications", clientToken);
        assertThat(feed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(feed.getBody()).contains("BOOKING_DECLINED").doesNotContainIgnoringCase("блок")
                .doesNotContainIgnoringCase("block");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The salon owner's own {@code masters} row (owner is always a master; master_type SALON_OWNER). */
    private UUID insertOwnerAsMaster(SalonFixture salon) {
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", UUID.class, salon.ownerEmail());
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, NOW(), NOW())",
                masterId, ownerId, salon.salonId());
        return masterId;
    }

    private List<java.time.Instant> slotStarts(String token, UUID masterId, UUID serviceId, ZonedDateTime day) {
        ResponseEntity<String> resp = exchange(HttpMethod.GET, "/api/v1/masters/" + masterId + "/slots?date="
                + day.toLocalDate() + "&serviceId=" + serviceId, token);
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
        List<java.time.Instant> starts = new ArrayList<>();
        for (JsonNode slot : data(resp).path("slots")) {
            starts.add(ZonedDateTime.parse(slot.path("startsAt").asText()).toInstant());
        }
        return starts;
    }

    private UUID createdId(ResponseEntity<String> resp) {
        assertCreated(resp);
        return UUID.fromString(data(resp).path("id").asText());
    }

    private void assertDeclinedByProvider(UUID bookingId) {
        var row = jdbcTemplate.queryForMap(
                "SELECT status, cancellation_reason, provider_comment FROM bookings WHERE id = ?", bookingId);
        assertThat(row.get("status")).as("status of %s", bookingId).isEqualTo("DECLINED");
        assertThat(row.get("cancellation_reason")).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(row.get("provider_comment")).isNull();
    }

    private String statusOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private int outboxRowsFor(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE aggregate_id = ?", Integer.class, bookingId);
    }

    private int inAppRows(UUID recipientId, String type) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM in_app_notification WHERE recipient_user_id = ? AND type = ?",
                Integer.class, recipientId, type);
    }

    private UUID userOfMaster(UUID masterId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}

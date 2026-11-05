package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.GuestPartialCancelSupport.Visit;
import com.beautica.notification.service.BookingVisit;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static com.beautica.booking.GuestMessageExpectedCopy.CONFIRMATION_HEADLINE;
import static com.beautica.support.MimeMessageBodies.htmlBodyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 436 — the provider side of a guest partial cancel by link: ONE {@code CLIENT_CANCELLED} outbox row per request
 * whose payload names exactly the request's booking ids (and nothing else — no note), ONE in-app row per request that
 * a second partial cancel does not de-duplicate away, the drained provider e-mail listing exactly the cancelled
 * services, and no guest SMS.
 *
 * <p>The e-mail is rendered for real (Thymeleaf + MIME) over a mocked {@link JavaMailSender}, by re-pointing the base
 * class's {@link EmailNotificationService} mock — no extra bean override, so this class shares the
 * {@code GuestPartialCancelIT} context.
 */
@DisplayName("Phase 436 — guest partial cancel: one outbox row + one in-app row per request, exact e-mail, no SMS")
class GuestPartialCancelNotificationIT extends AbstractIntegrationTest {

    private static final List<String> NAMES = List.of("Стрижка-436", "Фарбування-436", "Укладка-436");
    private static final int DAYS_AHEAD = 3;
    private static final long NO_SMS_GRACE_MS = 2_000;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private GuestTokenProvider guestTokenProvider;
    @Autowired private Clock clock;
    @Autowired private SpringTemplateEngine templateEngine;
    @Autowired private NotificationOutboxDrainWorker drainWorker;

    @Value("${app.invite.from-email:noreply@beautica.app}")
    private String fromAddress;

    @MockBean private SmsService smsService;

    private GuestPartialCancelSupport guest;
    private JavaMailSender mailSender;
    private Visit visit;

    @BeforeEach
    void bookVisitAndWireRealCancelEmail() throws Exception {
        guest = new GuestPartialCancelSupport(
                restTemplate, objectMapper, guestTokenProvider, jdbcTemplate, passwordEncoder, clock);
        String slug = guest.masterSlug("notify");
        visit = guest.bookVisit(slug, guest.services(slug, NAMES), GuestPartialCancelSupport.GUEST_PHONE, DAYS_AHEAD);

        mailSender = mock(JavaMailSender.class);
        Session session = Session.getInstance(new Properties());
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));
        EmailNotificationService real = new EmailNotificationService(mailSender, templateEngine, fromAddress);
        doAnswer(inv -> {
            real.sendClientCancelledEmail(inv.getArgument(0), inv.<BookingVisit>getArgument(1));
            return null;
        }).when(emailNotificationService).sendClientCancelledEmail(anyString(), any(BookingVisit.class));
    }

    @Test
    @DisplayName("each partial cancel enqueues exactly ONE CLIENT_CANCELLED row keyed to its lead, listing exactly "
            + "its cancelled ids")
    void should_enqueueOneOutboxRowListingExactlyCancelledIds_when_eachPartialCancelSucceeds() throws Exception {
        assertThat(guest.post(visit.token(), List.of(visit.line(1))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelOutboxRows()).as("first request → one row").hasSize(1);

        assertThat(guest.post(visit.token(), List.of(visit.line(2), visit.line(0))).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> rows = cancelOutboxRows();
        assertThat(rows).as("second request → exactly one more row").hasSize(2);
        assertThat(rows).extracting(row -> row.get("aggregate_id"))
                .as("lead = first cancelled line in visit order")
                .containsExactly(visit.line(1), visit.line(0));
        assertThat(rows).extracting(row -> cancelledIds((String) row.get("payload")))
                .containsExactly(List.of(visit.line(1)), List.of(visit.line(0), visit.line(2)));
    }

    @Test
    @DisplayName("the outbox payload carries only the booking ids — never a note")
    void should_keepNotesOutOfPayload_when_partialCancelEnqueued() throws Exception {
        assertThat(guest.post(visit.token(), List.of(visit.line(0))).getStatusCode()).isEqualTo(HttpStatus.OK);

        String payload = (String) cancelOutboxRows().get(0).get("payload");

        assertThat(objectMapper.readValue(payload, new TypeReference<Map<String, String>>() { }))
                .containsOnlyKeys(NotificationOutboxService.CANCELLED_BOOKING_IDS);
        assertThat(payload).doesNotContain(GuestPartialCancelSupport.CREATION_NOTE);
    }

    @Test
    @DisplayName("two sequential partial cancels write TWO in-app rows for the provider — the second is not "
            + "de-duplicated away")
    void should_writeTwoInAppRows_when_twoSequentialPartialCancels() throws Exception {
        assertThat(guest.post(visit.token(), List.of(visit.line(0))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(guest.post(visit.token(), List.of(visit.line(1))).getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> dedupKeys = jdbcTemplate.queryForList(
                "SELECT dedup_key FROM in_app_notification WHERE type = 'BOOKING_CANCELLED_BY_CLIENT' "
                        + "AND appointment_id = ?", String.class, visit.appointmentId());

        assertThat(dedupKeys).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the drained provider e-mail of a partial cancel lists exactly the services cancelled in that request")
    void should_listExactlyCancelledServicesInEmail_when_partialCancelsDrained() throws Exception {
        assertThat(guest.post(visit.token(), List.of(visit.line(1))).getStatusCode()).isEqualTo(HttpStatus.OK);
        drainWorker.drain();
        String first = singleCancelEmailBody();
        clearInvocations(mailSender);

        assertThat(guest.post(visit.token(), List.of(visit.line(0), visit.line(2))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        drainWorker.drain();
        String second = singleCancelEmailBody();

        assertThat(first).contains(NAMES.get(1)).doesNotContain(NAMES.get(0)).doesNotContain(NAMES.get(2));
        assertThat(second).contains(NAMES.get(0), NAMES.get(2)).doesNotContain(NAMES.get(1));
        assertThat(second.indexOf(NAMES.get(0))).as("visit order").isLessThan(second.indexOf(NAMES.get(2)));
    }

    @Test
    @DisplayName("a guest partial cancel sends the guest no SMS")
    void should_sendGuestNoSms_when_partialCancel() throws Exception {
        assertThat(guest.post(visit.token(), List.of(visit.line(1))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(guest.post(visit.token(), List.of(visit.line(0))).getStatusCode()).isEqualTo(HttpStatus.OK);

        // The (possibly async) creation confirmation is the only guest SMS allowed.
        verify(smsService, after(NO_SMS_GRACE_MS).never()).send(eq(GuestPartialCancelSupport.GUEST_PHONE),
                argThat(text -> !text.startsWith(CONFIRMATION_HEADLINE)));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> cancelOutboxRows() {
        return jdbcTemplate.queryForList(
                "SELECT aggregate_id, payload FROM notification_outbox WHERE event_type = 'CLIENT_CANCELLED' "
                        + "ORDER BY created_at, id");
    }

    private List<UUID> cancelledIds(String payload) {
        try {
            Map<String, String> json = objectMapper.readValue(payload, new TypeReference<>() { });
            return Arrays.stream(json.get(NotificationOutboxService.CANCELLED_BOOKING_IDS).split(","))
                    .map(UUID::fromString).toList();
        } catch (Exception e) {
            throw new IllegalStateException("unreadable outbox payload", e);
        }
    }

    private String singleCancelEmailBody() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(1)).send(captor.capture());
        return htmlBodyOf(captor.getValue());
    }
}

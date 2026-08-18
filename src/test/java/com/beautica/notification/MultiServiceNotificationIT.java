package com.beautica.notification;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.notification.service.BookingVisit;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManagerFactory;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end regression for the multi-service visit notification defect: a visit booked with N
 * services against one master produced e-mails naming only ONE of them.
 *
 * <p><b>Why this class exists and why a slice test does not replace it.</b> A visit is 1
 * {@code Appointment} + N {@code Booking} rows, and exactly ONE outbox row per event is enqueued,
 * keyed to the lead booking. The bug lived in the payload assembly BETWEEN the drain worker and the
 * template — nothing hydrated the sibling rows — which is precisely the seam
 * {@code EmailTemplateRenderingTest} (a Thymeleaf slice fed a hand-built context) and
 * {@code EmailNotificationServiceTest} (a Mockito unit fed a hand-built {@link BookingVisit}) both
 * step over: each of them ASSUMES the visit was resolved correctly. The pre-existing
 * {@code AppointmentCreateIT} counted outbox rows and never looked inside one, so it locked the
 * buggy behaviour in rather than catching it. This test starts from a real HTTP
 * {@code POST /appointments} over real Postgres, drains the real outbox through the real
 * {@link NotificationOutboxDrainWorker} → {@code BookingVisitResolver} →
 * {@code NotificationService} → {@link EmailNotificationService} → Thymeleaf chain, and asserts on
 * the rendered {@link MimeMessage} body that actually would have gone to SMTP.
 *
 * <p><b>The one mocked seam.</b> {@link AbstractIntegrationTest} mocks
 * {@code EmailNotificationService} for every IT so no suite performs SMTP I/O. That mock is
 * re-pointed here at a REAL {@link EmailNotificationService} instance built over a mocked
 * {@link JavaMailSender} and the context's real {@link SpringTemplateEngine}: rendering, context
 * assembly and MIME construction all run for real, only the socket write is stubbed. The base mock
 * is left in place (rather than overridden as a bean) deliberately — declaring no extra
 * {@code @MockBean} keeps this class on the SAME cached Spring context as
 * {@code AppointmentCreateIT}, so it costs no additional container and no additional context boot.
 *
 * <p>Booking notes are never asserted or rendered here — the locked track-25 rule keeps them out of
 * the outbox payload entirely.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Multi-service visit — the drained e-mails name EVERY service, over real Postgres")
class MultiServiceNotificationIT extends AbstractIntegrationTest {

    private static final String NEW_BOOKING_SUBJECT = "Нове бронювання";
    private static final String CONFIRMED_SUBJECT = "Бронювання підтверджено";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SpringTemplateEngine templateEngine;

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private BookingRepository bookingRepository;

    @Value("${app.invite.from-email:noreply@beautica.app}")
    private String fromAddress;

    private BookingTestFixtures fixtures;
    private JavaMailSender mailSender;

    @BeforeEach
    void wireRealEmailRenderingOverAMockedTransport() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);

        mailSender = mock(JavaMailSender.class);
        // A FRESH MimeMessage per call — a single shared instance would let the second e-mail
        // overwrite the first and collapse both assertions onto one body.
        Session session = Session.getInstance(new Properties());
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));

        EmailNotificationService real =
                new EmailNotificationService(mailSender, templateEngine, fromAddress);
        doAnswer(inv -> {
            real.sendNewBookingEmail(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(emailNotificationService).sendNewBookingEmail(anyString(), any(BookingVisit.class));
        doAnswer(inv -> {
            real.sendBookingConfirmedEmail(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(emailNotificationService).sendBookingConfirmedEmail(anyString(), any(BookingVisit.class));
        // BOTH decline routes are wired: the whole-visit one and the per-item one must render for
        // real, or the test that pins them apart could only ever observe one of the two.
        doAnswer(inv -> {
            real.sendVisitDeclinedEmail(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(emailNotificationService).sendVisitDeclinedEmail(anyString(), any(BookingVisit.class));
        doAnswer(inv -> {
            real.sendBookingDeclinedEmail(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(emailNotificationService).sendBookingDeclinedEmail(anyString(), any(Booking.class));
    }

    @Test
    @DisplayName("a three-service visit drains into a provider e-mail and a client e-mail that each "
            + "name ALL THREE services in visit order — not just the lead one")
    void should_nameEveryServiceInBothCreateEmails_when_aThreeServiceVisitIsDrained() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit(
                "multi-notif", List.of("Стрижка", "Фарбування", "Укладка"));
        nameUser(visit.clientId(), "Марія", "Коваленко");
        nameUser(masterUserId(visit.masterId()), "Оксана", "Левченко");

        drainWorker.drain();

        assertThat(outboxRepository.findAll())
                .as("both create-time rows must reach SENT — a DEAD row means dispatch threw")
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.getStatus()).isEqualTo(OutboxStatus.SENT));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(2)).send(captor.capture());

        MimeMessage provider = messageWithSubject(captor.getAllValues(), NEW_BOOKING_SUBJECT);
        String providerBody = htmlBodyOf(provider);
        assertThat(recipientOf(provider))
                .as("the NEW_BOOKING e-mail goes to the master's own user account")
                .isEqualTo(emailOfUser(masterUserId(visit.masterId())));
        assertThat(providerBody)
                .as("the defect: the sibling services never reached the provider's e-mail. body=%s",
                        providerBody)
                .contains("Стрижка")
                .contains("Фарбування")
                .contains("Укладка");
        assertVisitOrder(providerBody);
        assertThat(providerBody)
                .as("3 × 60 min of service time, 10:00 Kyiv start, zero buffers")
                .contains("3 год (до 13:00)")
                .contains("Послуги");

        MimeMessage client = messageWithSubject(captor.getAllValues(), CONFIRMED_SUBJECT);
        String clientBody = htmlBodyOf(client);
        assertThat(recipientOf(client))
                .as("defect 2: a multi-service visit used to send the client NO confirmation at all")
                .isEqualTo(emailOfUser(visit.clientId()));
        assertThat(clientBody)
                .as("the client's confirmation must describe the whole visit. body=%s", clientBody)
                .contains("Марія Коваленко")
                .contains("Стрижка")
                .contains("Фарбування")
                .contains("Укладка")
                .contains("3 год (до 13:00)");
        assertVisitOrder(clientBody);
    }

    @Test
    @DisplayName("the dominant single-service path renders exactly ONE service row, the singular "
            + "«Послуга» label and NO «Тривалість» row — the pre-visit e-mail, unchanged")
    void should_renderTheLegacySingleServiceShape_when_aOneServiceVisitIsDrained() throws Exception {
        BookingTestFixtures.VisitFixture visit =
                fixtures.createConfirmedVisit("single-notif", List.of("Манікюр"));
        nameUser(visit.clientId(), "Марія", "Коваленко");
        nameUser(masterUserId(visit.masterId()), "Оксана", "Левченко");

        drainWorker.drain();

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(2)).send(captor.capture());

        for (MimeMessage message : captor.getAllValues()) {
            // Label/row CARDINALITY is counted over the markup only: both templates open with a
            // documentation comment that names «Послуга»/«Послуги» in prose, and counting that
            // would score the docs as if they were rendered rows.
            String body = markupOf(htmlBodyOf(message));
            assertThat(body)
                    .as("subject=%s must still name its one service", message.getSubject())
                    .contains("Манікюр");
            assertThat(countOccurrences(body, "Манікюр"))
                    .as("exactly one service row — a visit loop must not duplicate the lead")
                    .isEqualTo(1);
            assertThat(body)
                    .as("the visit-only rows must stay suppressed for a one-service booking")
                    .doesNotContain("Тривалість")
                    .doesNotContain("Послуги");
            assertThat(countOccurrences(body, "Послуга"))
                    .as("the singular label, printed exactly once")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("both create-time outbox rows key to the visit's EARLIEST-starting booking — the "
            + "lead the resolver rehydrates every sibling from")
    void should_keyBothCreateTimeEventsToTheLeadBooking_when_aThreeServiceVisitIsCreated() throws Exception {
        BookingTestFixtures.VisitFixture visit =
                fixtures.createConfirmedVisit("pair-notif", List.of("Стрижка", "Фарбування", "Укладка"));
        UUID leadBookingId = jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at, id LIMIT 1",
                UUID.class, visit.id());

        List<NotificationOutboxEntry> entries = outboxRepository.findAll();

        assertThat(entries)
                .as("one row per event for the WHOLE visit — never one per service")
                .hasSize(2)
                .extracting(NotificationOutboxEntry::getEventType)
                .containsExactlyInAnyOrder(OutboxEventType.NEW_BOOKING, OutboxEventType.STATUS_CHANGED);
        assertThat(entries)
                .extracting(NotificationOutboxEntry::getAggregateId)
                .as("keying either row at a NON-lead item would silently truncate the rendered list, "
                        + "because BookingVisit orders by startsAt from the row the outbox points at")
                .containsOnly(leadBookingId);
    }

    @Test
    @DisplayName("a WHOLE-VISIT provider decline mails the client EVERY cancelled service — naming "
            + "only the lead left them turning up for the rest")
    void should_nameEveryCancelledService_when_theProviderDeclinesTheWholeVisit() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit(
                "decline-notif", List.of("Стрижка", "Фарбування", "Укладка"));
        nameUser(visit.clientId(), "Марія", "Коваленко");
        drainWorker.drain();                       // flush the two create-time rows
        outboxRepository.deleteAll();
        org.mockito.Mockito.reset(mailSender);
        Session session = Session.getInstance(new Properties());
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));

        declineWholeVisit(visit);
        drainWorker.drain();

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(1)).send(captor.capture());
        String body = htmlBodyOf(captor.getValue());
        assertThat(recipientOf(captor.getValue())).isEqualTo(emailOfUser(visit.clientId()));
        assertThat(body)
                .as("declineAppointment terminates the header AND every item but enqueues ONE "
                        + "STATUS_CHANGED keyed to the lead — all three must still be named. body=%s", body)
                .contains("Стрижка")
                .contains("Фарбування")
                .contains("Укладка");
        assertVisitOrder(body);
    }

    @Test
    @DisplayName("a PER-SERVICE decline mails the client ONLY the cancelled service — the siblings "
            + "are still on and must not be announced as cancelled")
    void should_nameOnlyTheDeclinedService_when_theProviderDeclinesOneServiceOfTheVisit() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit(
                "item-decline-notif", List.of("Стрижка", "Фарбування", "Укладка"));
        nameUser(visit.clientId(), "Марія", "Коваленко");
        drainWorker.drain();
        outboxRepository.deleteAll();
        org.mockito.Mockito.reset(mailSender);
        Session session = Session.getInstance(new Properties());
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));
        // The MIDDLE item, so a "names the lead instead" regression is distinguishable from a
        // "names the right one" pass.
        UUID middle = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                UUID.class, visit.id()).get(1);

        declineOneService(visit, middle);
        drainWorker.drain();

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(1)).send(captor.capture());
        String body = markupOf(htmlBodyOf(captor.getValue()));
        assertThat(body)
                .as("the one declined service, and neither sibling. body=%s", body)
                .contains("Фарбування")
                .doesNotContain("Стрижка")
                .doesNotContain("Укладка");
    }

    @Test
    @DisplayName("the visit's sibling rows are hydrated ONCE per drain — both outbox rows of one "
            + "visit share the single batched query, never one query each")
    void should_hydrateTheVisitOnceForTheWholeBatch_when_bothCreateTimeRowsDrainTogether() throws Exception {
        fixtures.createConfirmedVisit("batch-notif", List.of("Стрижка", "Фарбування", "Укладка"));

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        drainWorker.drain();

        // NEW_BOOKING and STATUS_CHANGED are both keyed to the same lead booking. Resolving per
        // outbox entry ran this query twice per visit — and did so DURING the dispatch loop, which
        // is contractually connection-free while SMTP is in flight. Pinning 1 is what stops a
        // future edit moving hydration back inside the loop.
        assertThat(executionsOfSiblingQuery(stats))
                .as("one batched hydration for the whole drain, not one per outbox row")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a sibling row's master and client IDs are read off DETACHED, uninitialised proxies "
            + "— no further statement and no LazyInitializationException")
    void should_takeNoFurtherStatement_when_theResolverReadsSiblingPartyIdsOffDetachedProxies()
            throws Exception {
        // findByAppointmentIdsWithGraph deliberately fetches NEITHER b.master NOR b.client: no
        // consumer of a sibling row dereferences a non-identifier property on either, and fetching
        // them cost three joins plus a full users row (password_hash + client PII) per sibling,
        // resident in the drain's phase-2 working set for the whole SMTP loop. That saving is only
        // safe because an identifier resolves off the proxy's stored FK. This test is the gate on
        // that: the resolver's tenancy filter runs OUTSIDE any transaction, on rows detached when
        // the repository's own read-only transaction closed, so a regression that made getId()
        // initialise the proxy would be a LazyInitializationException in production, not a slow path.
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit(
                "proxy-id-notif", List.of("Стрижка", "Фарбування", "Укладка"));
        List<Booking> siblings = bookingRepository.findByAppointmentIdsWithGraph(List.of(visit.id()));
        assertThat(siblings).as("the fixture must yield the rows whose proxies are under test").hasSize(3);
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        // Exactly what BookingVisitResolver#belongsToSameParties touches, and nothing else.
        List<UUID> partyIds = siblings.stream()
                .flatMap(b -> Stream.of(b.getMaster().getId(), b.getClient().getId()))
                .toList();

        assertThat(partyIds)
                .as("both identifiers must actually resolve — a null here would defang the count below")
                .hasSize(6)
                .doesNotContainNull();
        assertThat(stats.getPrepareStatementCount())
                .as("reading the master/client identifiers off detached proxies must issue NOTHING")
                .isZero();
    }

    /**
     * Execution count of {@code BookingRepository#findByAppointmentIdsWithGraph} — matched by its
     * {@code IN} predicate over {@code b.appointment.id}, which no other JPQL in the drain path uses.
     */
    private static long executionsOfSiblingQuery(Statistics stats) {
        return java.util.Arrays.stream(stats.getQueries())
                .filter(q -> q.contains("b.appointment.id IN"))
                .mapToLong(q -> stats.getQueryStatistics(q).getExecutionCount())
                .sum();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** {@code PATCH /appointments/{id}/decline} — the provider cancels the WHOLE visit. */
    private void declineWholeVisit(BookingTestFixtures.VisitFixture visit) {
        HttpHeaders headers = fixtures.bearerHeaders(visit.masterToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/appointments/" + visit.id() + "/decline",
                HttpMethod.PATCH, new HttpEntity<>("{}", headers), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("the decline must succeed or the e-mail assertions below are vacuous — body: %s",
                        resp.getBody())
                .isTrue();
    }

    /** {@code PATCH /appointments/{id}/services/{bookingId}/decline} — ONE service line only. */
    private void declineOneService(BookingTestFixtures.VisitFixture visit, UUID bookingId) {
        HttpHeaders headers = fixtures.bearerHeaders(visit.masterToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/appointments/" + visit.id() + "/services/" + bookingId + "/decline",
                HttpMethod.PATCH, new HttpEntity<>("{}", headers), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("the per-service decline must succeed — body: %s", resp.getBody())
                .isTrue();
    }

    /** Service names must appear in visit (startsAt) order, never in repository iteration order. */
    private static void assertVisitOrder(String body) {
        assertThat(body.indexOf("Стрижка")).isLessThan(body.indexOf("Фарбування"));
        assertThat(body.indexOf("Фарбування")).isLessThan(body.indexOf("Укладка"));
    }

    private static MimeMessage messageWithSubject(List<MimeMessage> messages, String subject) {
        return messages.stream()
                .filter(m -> {
                    try {
                        return subject.equals(m.getSubject());
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("no e-mail was sent with subject " + subject));
    }

    private static String recipientOf(MimeMessage message) throws Exception {
        return message.getRecipients(Message.RecipientType.TO)[0].toString();
    }

    /**
     * The decoded {@code text/html} body of a (possibly nested) multipart message. JavaMail resolves
     * the transfer encoding and charset for us, so the Cyrillic copy comes back as plain text —
     * which is exactly what makes "does the body name every service?" assertable at all.
     */
    private static String htmlBodyOf(MimeMessage message) throws Exception {
        // MimeMessageHelper sets the multipart CONTENT but the matching Content-Type HEADER is only
        // written by saveChanges(); production gets that for free inside JavaMailSenderImpl#doSend.
        // Without it every isMimeType() check below reads the "text/plain" default and the body
        // comes back empty — a silently green test asserting on "".
        message.saveChanges();
        String html = htmlBodyOf((Part) message);
        assertThat(html)
                .as("no text/html part was extracted — the MIME shape changed, every content "
                        + "assertion below would be vacuously true")
                .isNotBlank();
        return html;
    }

    private static String htmlBodyOf(Part part) throws Exception {
        if (part.isMimeType("text/html")) {
            return (String) part.getContent();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            StringBuilder collected = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                collected.append(htmlBodyOf(multipart.getBodyPart(i)));
            }
            return collected.toString();
        }
        return "";
    }

    /** The rendered document from {@code <html} on — i.e. without its leading template comment. */
    private static String markupOf(String html) {
        int start = html.indexOf("<html");
        return start < 0 ? html : html.substring(start);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private void nameUser(UUID userId, String firstName, String lastName) {
        jdbcTemplate.update(
                "UPDATE users SET first_name = ?, last_name = ? WHERE id = ?",
                firstName, lastName, userId);
    }

    private UUID masterUserId(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private String emailOfUser(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}

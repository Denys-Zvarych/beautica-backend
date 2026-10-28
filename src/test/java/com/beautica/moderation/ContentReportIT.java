package com.beautica.moderation;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.moderation.dto.CreateReportRequest;
import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.service.ContentReportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 395 — {@code POST /api/v1/reports} end to end against real Postgres (V207): idempotent repeat, one
 * support e-mail per report, the unique index under concurrent submits, visibility 404 parity, self-report and
 * the per-user rate limit. {@code EmailService} is the shared {@code @MockBean} of
 * {@link AbstractIntegrationTest}; the after-commit listener runs on the request thread, so the e-mail call is
 * observable as soon as the response returns.
 */
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = "app.support.email=" + ContentReportIT.SUPPORT_EMAIL)
@DisplayName("POST /api/v1/reports — content report intake (phase 395)")
class ContentReportIT extends AbstractIntegrationTest {

    static final String SUPPORT_EMAIL = "moderation@beautica.test";

    private static final String URL = "/api/v1/reports";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ContentReportService contentReportService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("201 on the first report, 200 with the same report on the repeat; one row, one ids-only e-mail")
    void should_return201ThenIdempotent200_and_sendOneEmail() throws Exception {
        SalonFixture salon = createSalon();
        UUID clientId = createUser(unique("client"), "CLIENT", null);
        UUID reviewId = insertReview(clientId, salon, "Жахливий сервіс");
        String reporterEmail = unique("reporter");
        UUID reporterId = createUser(reporterEmail, "CLIENT", null);
        String token = tokenFor(reporterEmail);
        String body = reportBody("REVIEW", reviewId, "OFFENSIVE", "SECRET-COMMENT-TEXT");

        ResponseEntity<String> first = post(token, body);
        ResponseEntity<String> repeat = post(token, body);

        assertThat(first.getStatusCode()).as("first report — body: %s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(repeat.getStatusCode()).as("repeat — body: %s", repeat.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode created = data(first);
        assertThat(created.path("status").asText()).isEqualTo("OPEN");
        assertThat(created.path("createdAt").asText()).isNotBlank();
        assertThat(data(repeat).path("id").asText()).isEqualTo(created.path("id").asText());
        // The 201 body is built without re-reading the row; the 200 repeat reads it — they must agree.
        assertThat(java.time.Instant.parse(data(repeat).path("createdAt").asText()))
                .isEqualTo(java.time.Instant.parse(created.path("createdAt").asText()));
        assertThat(countReports(reporterId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT comment FROM content_reports WHERE reporter_user_id = ?", String.class, reporterId))
                .isEqualTo("SECRET-COMMENT-TEXT");
        verify(emailService, times(1)).sendContentReportEmail(
                SUPPORT_EMAIL, UUID.fromString(created.path("id").asText()), "REVIEW", reviewId, "OFFENSIVE",
                reporterId);
    }

    @Test
    @DisplayName("concurrent identical submits → exactly one row, one 201, the rest 200 with the same id, one e-mail")
    void should_keepOneRow_when_concurrentIdenticalSubmits() throws Exception {
        SalonFixture salon = createSalon();
        String reporterEmail = unique("racer");
        UUID reporterId = createUser(reporterEmail, "CLIENT", null);
        String token = tokenFor(reporterEmail);
        String body = reportBody("SALON_PROFILE", salon.salonId(), "SPAM", null);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return post(token, body);
                }));
            }
            start.countDown();

            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }

            assertThat(responses).extracting(ResponseEntity::getStatusCode)
                    .containsOnly(HttpStatus.CREATED, HttpStatus.OK)
                    .filteredOn(HttpStatus.CREATED::equals).hasSize(1);
            assertThat(responses).extracting(r -> data(r).path("id").asText()).containsOnly(
                    data(responses.get(0)).path("id").asText());
        } finally {
            pool.shutdownNow();
        }
        assertThat(countReports(reporterId)).isEqualTo(1);
        verify(emailService, times(1)).sendContentReportEmail(
                anyString(), any(), anyString(), any(), anyString(), eq(reporterId));
    }

    @Test
    @DisplayName("an invisible target (client profile without a shared booking) and a missing id answer the same 404")
    void should_return404WithIdenticalBody_when_targetInvisibleOrMissing() throws Exception {
        SalonFixture salon = createSalon();
        UUID strangerClientId = createUser(unique("stranger"), "CLIENT", null);
        String ownerToken = tokenFor(salon.ownerEmail());

        ResponseEntity<String> invisible = post(ownerToken, reportBody("CLIENT_PROFILE", strangerClientId, "SPAM", null));
        ResponseEntity<String> missing = post(ownerToken, reportBody("CLIENT_PROFILE", UUID.randomUUID(), "SPAM", null));

        assertThat(invisible.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withoutRequestId(invisible)).isEqualTo(withoutRequestId(missing));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_reports", Integer.class)).isZero();
        verify(emailService, never()).sendContentReportEmail(anyString(), any(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("a provider with a booking can report the client's profile and another provider's client review")
    void should_return201_when_providerSharesBookingWithClient() throws Exception {
        SalonFixture salon = createSalon();
        UUID clientId = createUser(unique("client"), "CLIENT", null);
        UUID bookingId = insertCompletedBooking(clientId, salon);
        UUID clientReviewId = insertClientReview(bookingId, clientId, salon);
        String masterToken = tokenFor(salon.masterEmail());
        String otherProviderEmail = unique("indep");
        IndependentMaster otherProvider = createIndependentMaster(otherProviderEmail);
        insertIndependentBooking(clientId, otherProvider.masterId());

        ResponseEntity<String> profile = post(masterToken, reportBody("CLIENT_PROFILE", clientId, "HARASSMENT", null));
        ResponseEntity<String> clientReview = post(tokenFor(otherProviderEmail),
                reportBody("CLIENT_REVIEW", clientReviewId, "FALSE_INFO", null));

        assertThat(profile.getStatusCode()).as("body: %s", profile.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(clientReview.getStatusCode()).as("body: %s", clientReview.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("salon owner / assigned admin reporting a salon client review (written under the master) → 400")
    void should_return400_when_ownerReportsSalonClientReview() throws Exception {
        SalonFixture salon = createSalon();
        UUID clientId = createUser(unique("client"), "CLIENT", null);
        UUID bookingId = insertCompletedBooking(clientId, salon);
        UUID clientReviewId = insertClientReview(bookingId, clientId, salon);
        String adminEmail = unique("admin");
        createUser(adminEmail, "SALON_ADMIN", salon.salonId());

        ResponseEntity<String> byOwner = post(tokenFor(salon.ownerEmail()),
                reportBody("CLIENT_REVIEW", clientReviewId, "FALSE_INFO", null));
        ResponseEntity<String> byAdmin = post(tokenFor(adminEmail),
                reportBody("CLIENT_REVIEW", clientReviewId, "FALSE_INFO", null));

        assertThat(byOwner.getStatusCode()).as("body: %s", byOwner.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(byAdmin.getStatusCode()).as("body: %s", byAdmin.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countReportsOnTarget("CLIENT_REVIEW", clientReviewId)).isZero();
        verify(emailService, never()).sendContentReportEmail(anyString(), any(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("a client can report a master profile and a portfolio photo")
    void should_return201_when_clientReportsMasterProfileAndPhoto() throws Exception {
        SalonFixture salon = createSalon();
        UUID photoId = insertMasterPortfolioPhoto(salon.masterId());
        String clientEmail = unique("client");
        createUser(clientEmail, "CLIENT", null);
        String token = tokenFor(clientEmail);

        ResponseEntity<String> profile = post(token, reportBody("MASTER_PROFILE", salon.masterId(), "FALSE_INFO", null));
        ResponseEntity<String> photo = post(token, reportBody("PORTFOLIO_PHOTO", photoId, "SEXUAL_CONTENT", null));

        assertThat(profile.getStatusCode()).as("body: %s", profile.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(photo.getStatusCode()).as("body: %s", photo.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("reporting one's own review → 400, nothing stored, no e-mail")
    void should_return400_when_reportingOwnReview() throws Exception {
        SalonFixture salon = createSalon();
        String clientEmail = unique("author");
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        UUID reviewId = insertReview(clientId, salon, "Мій відгук");

        ResponseEntity<String> resp = post(tokenFor(clientEmail), reportBody("REVIEW", reviewId, "SPAM", null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countReports(clientId)).isZero();
        verify(emailService, never()).sendContentReportEmail(anyString(), any(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("OTHER without a comment → 400, nothing stored")
    void should_return400_when_otherWithoutComment() throws Exception {
        SalonFixture salon = createSalon();
        String clientEmail = unique("other");
        UUID clientId = createUser(clientEmail, "CLIENT", null);

        ResponseEntity<String> resp = post(tokenFor(clientEmail), reportBody("SALON_PROFILE", salon.salonId(), "OTHER", " "));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countReports(clientId)).isZero();
    }

    @Test
    @DisplayName("no token → 401")
    void should_return401_when_unauthenticated() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = restTemplate.exchange(URL, HttpMethod.POST,
                new HttpEntity<>(reportBody("SALON_PROFILE", UUID.randomUUID(), "SPAM", null), headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── QA: target type × role matrix (phase 395 D2/D5/D8), end to end ─────────────────────

    @Test
    @DisplayName("REVIEW — every provider role (INDEPENDENT_MASTER, SALON_ADMIN, other salon's SALON_MASTER and "
            + "SALON_OWNER) can report a public review → 201 each, one row and one e-mail per reporter")
    void should_return201_when_everyProviderRoleReportsPublicReview() throws Exception {
        SalonFixture salon = createSalon();
        UUID reviewId = insertReview(createUser(unique("client"), "CLIENT", null), salon, "Жахливо");
        SalonFixture otherSalon = createSalon();
        String indepEmail = unique("indep");
        UUID indepUserId = createIndependentMaster(indepEmail).userId();
        String adminEmail = unique("admin");
        UUID adminId = createUser(adminEmail, "SALON_ADMIN", otherSalon.salonId());
        List<String> reporterTokens = List.of(tokenFor(indepEmail), tokenFor(adminEmail),
                tokenFor(otherSalon.masterEmail()), tokenFor(otherSalon.ownerEmail()));

        List<Integer> statuses = new ArrayList<>();
        for (String token : reporterTokens) {
            statuses.add(post(token, reportBody("REVIEW", reviewId, "OFFENSIVE", null)).getStatusCode().value());
        }

        assertThat(statuses).as("INDEPENDENT_MASTER, SALON_ADMIN, SALON_MASTER, SALON_OWNER")
                .containsExactly(201, 201, 201, 201);
        assertThat(countReports(indepUserId)).isEqualTo(1);
        assertThat(countReports(adminId)).isEqualTo(1);
        assertThat(countReportsOnTarget("REVIEW", reviewId)).isEqualTo(4);
        verify(emailService, times(4)).sendContentReportEmail(
                eq(SUPPORT_EMAIL), any(), eq("REVIEW"), eq(reviewId), eq("OFFENSIVE"), any());
    }

    @Test
    @DisplayName("salon PORTFOLIO_PHOTO — CLIENT, the salon's own SALON_MASTER and an INDEPENDENT_MASTER → 201 each")
    void should_return201_when_nonManagersReportSalonPortfolioPhoto() throws Exception {
        SalonFixture salon = createSalon();
        UUID photoId = insertSalonPortfolioPhoto(salon.salonId());
        String clientEmail = unique("client");
        createUser(clientEmail, "CLIENT", null);
        String indepEmail = unique("indep");
        createIndependentMaster(indepEmail);

        ResponseEntity<String> byClient = post(tokenFor(clientEmail),
                reportBody("PORTFOLIO_PHOTO", photoId, "SEXUAL_CONTENT", null));
        ResponseEntity<String> bySalonMaster = post(tokenFor(salon.masterEmail()),
                reportBody("PORTFOLIO_PHOTO", photoId, "OFFENSIVE", null));
        ResponseEntity<String> byIndep = post(tokenFor(indepEmail),
                reportBody("PORTFOLIO_PHOTO", photoId, "SPAM", null));

        assertThat(byClient.getStatusCode()).as("CLIENT — body: %s", byClient.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(bySalonMaster.getStatusCode()).as("salon's own SALON_MASTER (not a manager) — body: %s",
                bySalonMaster.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(byIndep.getStatusCode()).as("INDEPENDENT_MASTER — body: %s", byIndep.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(countReportsOnTarget("PORTFOLIO_PHOTO", photoId)).isEqualTo(3);
    }

    @Test
    @DisplayName("salon PORTFOLIO_PHOTO — the salon's SALON_OWNER and assigned SALON_ADMIN → 400 (own content), "
            + "nothing stored, no e-mail")
    void should_return400_when_salonManagersReportOwnSalonPhoto() throws Exception {
        SalonFixture salon = createSalon();
        UUID photoId = insertSalonPortfolioPhoto(salon.salonId());
        String adminEmail = unique("admin");
        createUser(adminEmail, "SALON_ADMIN", salon.salonId());

        ResponseEntity<String> byOwner = post(tokenFor(salon.ownerEmail()),
                reportBody("PORTFOLIO_PHOTO", photoId, "SPAM", null));
        ResponseEntity<String> byAdmin = post(tokenFor(adminEmail),
                reportBody("PORTFOLIO_PHOTO", photoId, "SPAM", null));

        assertThat(byOwner.getStatusCode()).as("owner — body: %s", byOwner.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(byAdmin.getStatusCode()).as("assigned admin — body: %s", byAdmin.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countReportsOnTarget("PORTFOLIO_PHOTO", photoId)).isZero();
        verify(emailService, never()).sendContentReportEmail(anyString(), any(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("SALON_PROFILE — the salon's own SALON_MASTER, an INDEPENDENT_MASTER and another salon's owner → 201; "
            + "the owner and assigned SALON_ADMIN → 400")
    void should_enforceOwnershipMatrix_when_reportingSalonProfile() throws Exception {
        SalonFixture salon = createSalon();
        SalonFixture otherSalon = createSalon();
        String indepEmail = unique("indep");
        createIndependentMaster(indepEmail);
        String adminEmail = unique("admin");
        createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        String body = reportBody("SALON_PROFILE", salon.salonId(), "FALSE_INFO", null);

        ResponseEntity<String> byOwnMaster = post(tokenFor(salon.masterEmail()), body);
        ResponseEntity<String> byIndep = post(tokenFor(indepEmail), body);
        ResponseEntity<String> byOtherOwner = post(tokenFor(otherSalon.ownerEmail()), body);
        ResponseEntity<String> byOwner = post(tokenFor(salon.ownerEmail()), body);
        ResponseEntity<String> byAdmin = post(tokenFor(adminEmail), body);

        assertThat(byOwnMaster.getStatusCode()).as("salon's SALON_MASTER").isEqualTo(HttpStatus.CREATED);
        assertThat(byIndep.getStatusCode()).as("INDEPENDENT_MASTER").isEqualTo(HttpStatus.CREATED);
        assertThat(byOtherOwner.getStatusCode()).as("another salon's owner").isEqualTo(HttpStatus.CREATED);
        assertThat(byOwner.getStatusCode()).as("own salon — owner").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(byAdmin.getStatusCode()).as("own salon — assigned admin").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countReportsOnTarget("SALON_PROFILE", salon.salonId())).isEqualTo(3);
    }

    @Test
    @DisplayName("SALON_PROFILE of an inactive (deleted) salon → 404 with the same body as a missing id")
    void should_return404WithIdenticalBody_when_salonInactive() throws Exception {
        SalonFixture salon = createSalon();
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salon.salonId());
        String clientEmail = unique("client");
        createUser(clientEmail, "CLIENT", null);
        String token = tokenFor(clientEmail);

        ResponseEntity<String> inactive = post(token, reportBody("SALON_PROFILE", salon.salonId(), "SPAM", null));
        ResponseEntity<String> missing = post(token, reportBody("SALON_PROFILE", UUID.randomUUID(), "SPAM", null));

        assertThat(inactive.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withoutRequestId(inactive)).isEqualTo(withoutRequestId(missing));
        assertThat(countReportsOnTarget("SALON_PROFILE", salon.salonId())).isZero();
    }

    @Test
    @DisplayName("MASTER_PROFILE — a SALON_MASTER reports another master → 201; an INDEPENDENT_MASTER reports "
            + "themselves → 400")
    void should_return201ForOtherMaster_and_400ForSelf_when_reportingMasterProfile() throws Exception {
        SalonFixture salon = createSalon();
        String indepEmail = unique("indep");
        UUID indepMasterId = createIndependentMaster(indepEmail).masterId();

        ResponseEntity<String> byColleague = post(tokenFor(salon.masterEmail()),
                reportBody("MASTER_PROFILE", indepMasterId, "FALSE_INFO", null));
        ResponseEntity<String> bySelf = post(tokenFor(indepEmail),
                reportBody("MASTER_PROFILE", indepMasterId, "FALSE_INFO", null));

        assertThat(byColleague.getStatusCode()).as("body: %s", byColleague.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(bySelf.getStatusCode()).as("body: %s", bySelf.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countReportsOnTarget("MASTER_PROFILE", indepMasterId)).isEqualTo(1);
    }

    @Test
    @DisplayName("MASTER_PROFILE of a detached master (account deleted) → 404 with the same body as a missing id")
    void should_return404WithIdenticalBody_when_masterDetached() throws Exception {
        SalonFixture salon = createSalon();
        jdbcTemplate.update(
                "UPDATE masters SET user_id = NULL, detached_first_name = 'Тест', detached_last_name = 'Майстер', "
                        + "detached_at = NOW(), is_active = false WHERE id = ?",
                salon.masterId());
        String clientEmail = unique("client");
        createUser(clientEmail, "CLIENT", null);
        String token = tokenFor(clientEmail);

        ResponseEntity<String> detached = post(token, reportBody("MASTER_PROFILE", salon.masterId(), "SPAM", null));
        ResponseEntity<String> missing = post(token, reportBody("MASTER_PROFILE", UUID.randomUUID(), "SPAM", null));

        assertThat(detached.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withoutRequestId(detached)).isEqualTo(withoutRequestId(missing));
    }

    @Test
    @DisplayName("CLIENT_PROFILE — an INDEPENDENT_MASTER with a booking and the salon's assigned SALON_ADMIN "
            + "(booking at their salon) → 201")
    void should_return201_when_indepMasterOrAssignedAdminSharesBooking() throws Exception {
        SalonFixture salon = createSalon();
        UUID clientId = createUser(unique("client"), "CLIENT", null);
        insertCompletedBooking(clientId, salon);
        String indepEmail = unique("indep");
        IndependentMaster indep = createIndependentMaster(indepEmail);
        UUID otherClientId = createUser(unique("client2"), "CLIENT", null);
        insertIndependentBooking(otherClientId, indep.masterId());
        String adminEmail = unique("admin");
        createUser(adminEmail, "SALON_ADMIN", salon.salonId());

        ResponseEntity<String> byIndep = post(tokenFor(indepEmail),
                reportBody("CLIENT_PROFILE", otherClientId, "HARASSMENT", null));
        ResponseEntity<String> byAdmin = post(tokenFor(adminEmail),
                reportBody("CLIENT_PROFILE", clientId, "HARASSMENT", null));

        assertThat(byIndep.getStatusCode()).as("indep — body: %s", byIndep.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(byAdmin.getStatusCode()).as("admin — body: %s", byAdmin.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("CLIENT_PROFILE — another salon's SALON_ADMIN, an INDEPENDENT_MASTER and a CLIENT without a shared "
            + "booking → 404 (same body as missing); the client reporting themselves → 400")
    void should_return404_when_noSharedBooking_and_400_when_clientReportsSelf() throws Exception {
        SalonFixture salon = createSalon();
        String clientEmail = unique("client");
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        insertCompletedBooking(clientId, salon);
        SalonFixture otherSalon = createSalon();
        String foreignAdminEmail = unique("admin");
        createUser(foreignAdminEmail, "SALON_ADMIN", otherSalon.salonId());
        String indepEmail = unique("indep");
        createIndependentMaster(indepEmail);
        String otherClientEmail = unique("client2");
        createUser(otherClientEmail, "CLIENT", null);
        String body = reportBody("CLIENT_PROFILE", clientId, "SPAM", null);
        ResponseEntity<String> missing = post(tokenFor(indepEmail),
                reportBody("CLIENT_PROFILE", UUID.randomUUID(), "SPAM", null));

        ResponseEntity<String> byForeignAdmin = post(tokenFor(foreignAdminEmail), body);
        ResponseEntity<String> byIndep = post(tokenFor(indepEmail), body);
        ResponseEntity<String> byOtherClient = post(tokenFor(otherClientEmail), body);
        ResponseEntity<String> bySelf = post(tokenFor(clientEmail), body);

        assertThat(withoutRequestId(byForeignAdmin)).as("another salon's admin").isEqualTo(withoutRequestId(missing));
        assertThat(withoutRequestId(byIndep)).as("INDEPENDENT_MASTER, no booking").isEqualTo(withoutRequestId(missing));
        assertThat(withoutRequestId(byOtherClient)).as("another CLIENT").isEqualTo(withoutRequestId(missing));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bySelf.getStatusCode()).as("self — body: %s", bySelf.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countReportsOnTarget("CLIENT_PROFILE", clientId)).isZero();
    }

    @Test
    @DisplayName("CLIENT_REVIEW — an INDEPENDENT_MASTER who also booked the reviewed client → 201; the reviewed "
            + "CLIENT and a non-author SALON_MASTER without a booking → 404; the authoring master → 400")
    void should_enforceVisibilityMatrix_when_reportingClientReview() throws Exception {
        SalonFixture salon = createSalon();
        String clientEmail = unique("client");
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        UUID bookingId = insertCompletedBooking(clientId, salon);
        UUID clientReviewId = insertClientReview(bookingId, clientId, salon);
        String indepEmail = unique("indep");
        insertIndependentBooking(clientId, createIndependentMaster(indepEmail).masterId());
        String colleagueEmail = unique("colleague");
        UUID colleagueUserId = createUser(colleagueEmail, "SALON_MASTER", salon.salonId());
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                UUID.randomUUID(), colleagueUserId, salon.salonId());
        String body = reportBody("CLIENT_REVIEW", clientReviewId, "FALSE_INFO", null);

        ResponseEntity<String> byIndep = post(tokenFor(indepEmail), body);
        ResponseEntity<String> bySubject = post(tokenFor(clientEmail), body);
        ResponseEntity<String> byColleague = post(tokenFor(colleagueEmail), body);
        ResponseEntity<String> byAuthor = post(tokenFor(salon.masterEmail()), body);

        assertThat(byIndep.getStatusCode()).as("indep — body: %s", byIndep.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(bySubject.getStatusCode()).as("reviewed client").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(byColleague.getStatusCode()).as("non-author salon master, no booking").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(byAuthor.getStatusCode()).as("author").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countReportsOnTarget("CLIENT_REVIEW", clientReviewId)).isEqualTo(1);
    }

    // ── QA: D6 rate limit is per user, a 429 sends nothing ───────────────────────────────────

    @Test
    @DisplayName("after a user's 20 reports the 21st → 429 with Retry-After 86400, no row, no 21st e-mail; "
            + "a repeat of an earlier report still answers 200; a different user is unaffected (201)")
    void should_return429OnlyForExhaustedUser_and_sendNoEmail_when_budgetExhausted() throws Exception {
        String floodEmail = unique("flood");
        UUID floodId = createUser(floodEmail, "CLIENT", null);
        String floodToken = tokenFor(floodEmail);
        String bystanderEmail = unique("bystander");
        createUser(bystanderEmail, "CLIENT", null);
        UUID ownerId = createUser(unique("flood-owner"), "SALON_OWNER", null);
        List<UUID> salonIds = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            salonIds.add(insertSalon(ownerId));
        }
        for (int i = 0; i < 20; i++) {
            post(floodToken, reportBody("SALON_PROFILE", salonIds.get(i), "SPAM", null));
        }

        ResponseEntity<String> over = post(floodToken, reportBody("SALON_PROFILE", salonIds.get(20), "SPAM", null));
        ResponseEntity<String> repeat = post(floodToken, reportBody("SALON_PROFILE", salonIds.get(0), "SPAM", null));
        ResponseEntity<String> bystander = post(tokenFor(bystanderEmail),
                reportBody("SALON_PROFILE", salonIds.get(20), "SPAM", null));

        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(over.getHeaders().getFirst("Retry-After")).isEqualTo("86400");
        assertThat(objectMapper.readTree(over.getBody()).path("success").asBoolean(true)).isFalse();
        assertThat(repeat.getStatusCode()).as("D4 repeat spends no token").isEqualTo(HttpStatus.OK);
        assertThat(bystander.getStatusCode()).as("per-user bucket — body: %s", bystander.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(countReports(floodId)).isEqualTo(20);
        verify(emailService, never()).sendContentReportEmail(
                anyString(), any(), anyString(), eq(salonIds.get(20)), anyString(), eq(floodId));
        verify(emailService, times(20)).sendContentReportEmail(
                anyString(), any(), anyString(), any(), anyString(), eq(floodId));
    }

    // ── QA: D7 e-mail is sent after COMMIT only ──────────────────────────────────────────────

    @Test
    @DisplayName("the support e-mail is sent only once the report row is committed — visible from another "
            + "connection at the moment of the send")
    void should_sendEmailAfterCommit_when_reportCreated() throws Exception {
        SalonFixture salon = createSalon();
        String clientEmail = unique("client");
        createUser(clientEmail, "CLIENT", null);
        AtomicInteger committedRowsAtSend = new AtomicInteger(-1);
        doAnswer(invocation -> {
            // Read on ANOTHER thread: on this one jdbcTemplate would join the request's bound JPA connection
            // and see the uncommitted row. A fresh pooled connection sees only COMMITTED rows.
            UUID reportId = invocation.getArgument(1);
            committedRowsAtSend.set(CompletableFuture.supplyAsync(() -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM content_reports WHERE id = ?", Integer.class, reportId))
                    .get(30, TimeUnit.SECONDS));
            return null;
        }).when(emailService).sendContentReportEmail(anyString(), any(), anyString(), any(), anyString(), any());

        ResponseEntity<String> resp = post(tokenFor(clientEmail), reportBody("SALON_PROFILE", salon.salonId(), "SPAM", null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(committedRowsAtSend.get())
                .as("row must already be committed when the e-mail fires (-1 = e-mail never sent)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a report created inside a transaction that rolls back → no row, no support e-mail")
    void should_sendNoEmail_when_reportTransactionRollsBack() {
        SalonFixture salon = createSalon();
        UUID clientId = createUser(unique("client"), "CLIENT", null);
        CreateReportRequest request = new CreateReportRequest(
                ReportTargetType.SALON_PROFILE, salon.salonId(), ReportReason.SPAM, null);

        Boolean createdInsideTx = transactionTemplate.execute(status -> {
            boolean created = contentReportService.createReport(clientId, Role.CLIENT, request).created();
            status.setRollbackOnly();
            return created;
        });

        assertThat(createdInsideTx).as("precondition: the service did create the row").isTrue();
        assertThat(countReports(clientId)).isZero();
        verify(emailService, never()).sendContentReportEmail(anyString(), any(), anyString(), any(), anyString(), any());
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<String> post(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String tokenFor(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    private JsonNode data(ResponseEntity<String> resp) {
        try {
            return objectMapper.readTree(resp.getBody()).path("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode withoutRequestId(ResponseEntity<String> resp) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(resp.getBody());
        node.remove("requestId");
        return node;
    }

    private String reportBody(String targetType, UUID targetId, String reason, String comment) {
        ObjectNode node = objectMapper.createObjectNode()
                .put("targetType", targetType)
                .put("targetId", targetId.toString())
                .put("reason", reason);
        if (comment != null) {
            node.put("comment", comment);
        }
        return node.toString();
    }

    private int countReports(UUID reporterId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_reports WHERE reporter_user_id = ?", Integer.class, reporterId);
    }

    private static String unique(String prefix) {
        return "report-" + prefix + "-" + UUID.randomUUID() + "@beautica.test";
    }

    // ── seeding fixtures (local by house convention — mirrors ClientReviewIT) ───────

    private record SalonFixture(UUID salonId, String ownerEmail, UUID masterId, String masterEmail) {}

    private SalonFixture createSalon() {
        String ownerEmail = unique("owner");
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = insertSalon(ownerId);
        String masterEmail = unique("master");
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return new SalonFixture(salonId, ownerEmail, masterId, masterEmail);
    }

    private UUID insertSalon(UUID ownerId) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());
        return salonId;
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private UUID insertCompletedBooking(UUID clientId, SalonFixture salon) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salon.salonId(), resolveUnusedServiceTypeId("SALON", salon.salonId()));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, salon.masterId(), serviceDefId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, salon.masterId(), masterServiceId, salon.salonId());
        return bookingId;
    }

    private UUID insertReview(UUID clientId, SalonFixture salon, String comment) {
        UUID bookingId = insertCompletedBooking(clientId, salon);
        UUID reviewId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, comment, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 1, ?, NOW(), NOW())",
                reviewId, bookingId, clientId, salon.masterId(), salon.salonId(), comment);
        return reviewId;
    }

    private UUID insertClientReview(UUID bookingId, UUID clientId, SalonFixture salon) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO client_reviews (id, booking_id, subject_client_id, author_master_id, salon_id, rating, "
                        + "comment, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 2, 'Запізнився', NOW(), NOW())",
                id, bookingId, clientId, salon.masterId(), salon.salonId());
        return id;
    }

    private UUID insertMasterPortfolioPhoto(UUID masterId) {
        UUID id = UUID.randomUUID();
        String r2Key = "portfolio/master/" + masterId + "/" + id + ".jpg";
        jdbcTemplate.update(
                "INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, r2_key, r2_url, "
                        + "created_at, updated_at) VALUES (?, NULL, 'MASTER', ?, 'PORTFOLIO', ?, ?, NOW(), NOW())",
                id, masterId, r2Key, "https://pub.example.r2.dev/" + r2Key);
        return id;
    }

    private int countReportsOnTarget(String targetType, UUID targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_reports WHERE target_type = ? AND target_id = ?",
                Integer.class, targetType, targetId);
    }

    private record IndependentMaster(UUID userId, UUID masterId) {}

    private IndependentMaster createIndependentMaster(String email) {
        UUID userId = createUser(email, "INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, userId);
        return new IndependentMaster(userId, masterId);
    }

    private UUID insertIndependentBooking(UUID clientId, UUID masterId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Indep Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, masterId, resolveUnusedServiceTypeId("INDEPENDENT_MASTER", masterId));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'COMPLETED', NOW() - interval '5 hours', NOW() - interval '4 hours', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId);
        return bookingId;
    }

    private UUID insertSalonPortfolioPhoto(UUID salonId) {
        UUID id = UUID.randomUUID();
        String r2Key = "portfolio/salon/" + salonId + "/" + id + ".jpg";
        jdbcTemplate.update(
                "INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, r2_key, r2_url, "
                        + "created_at, updated_at) VALUES (?, NULL, 'SALON', ?, 'PORTFOLIO', ?, ?, NOW(), NOW())",
                id, salonId, r2Key, "https://pub.example.r2.dev/" + r2Key);
        return id;
    }
}

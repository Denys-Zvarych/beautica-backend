package com.beautica.client.provider;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.retention.GuestPiiAnonymisationService;
import com.beautica.client.service.ClientPhoneAttachService;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ConflictException;
import com.beautica.review.dto.CreateClientReviewRequest;
import com.beautica.review.service.ClientReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * B546 (REV-38, REV-41, CLI-43, CLI-59, CLI-64) — providers rate guest / walk-in clients by phone, the guest card and
 * list read the phone's rating, a phone verification moves it to the account (UserPhoneService and the sweep), the V269
 * CHECKs hold, and the flag / write parity holds for every provider role and booking kind.
 */
@DisplayName("Guest / walk-in client ratings by phone — B546")
class ClientReviewGuestIT extends AbstractClientRatingSubjectIT {

    @Autowired private ClientPhoneAttachService attachService;
    @Autowired private ClientReviewService clientReviewService;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private GuestPiiAnonymisationService anonymisationService;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("rate a LINK guest: the row is phone-keyed; guest card, /ratings and the list row all read count 1")
    void should_readGuestRatingEverywhere_when_ownerRatesALinkGuest() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        UUID booking = completedGuest(s, "LINK", phone);
        String owner = fx.token(s.ownerEmail());

        rateOk(owner, booking, 4, "Вчасно");

        Map<String, Object> row = reviewRow(booking);
        assertThat(row.get("subject_guest_phone")).isEqualTo(phone);
        assertThat(row.get("subject_client_id")).isNull();
        JsonNode card = objectMapper.readTree(fx.get(owner, guestCardUrl(s.salonId(), booking)).getBody()).path("data");
        assertThat(card.path("ratingCount").asInt()).isEqualTo(1);
        assertThat(card.path("ratingAvg").decimalValue()).isEqualByComparingTo("4");
        assertThat(card.path("ratingDistribution").get(1).path("count").asLong()).as("4 stars bucket").isEqualTo(1);
        JsonNode ratings = objectMapper.readTree(
                fx.get(owner, guestCardUrl(s.salonId(), booking) + "/ratings").getBody());
        assertThat(ratings.at("/data/totalElements").asLong()).isEqualTo(1);
        assertThat(ratings.at("/data/data/0/stars").asInt()).isEqualTo(4);
        assertThat(ratings.toString()).as("REV-36: no comment on the wire").doesNotContain("Вчасно");
        JsonNode list = objectMapper.readTree(fx.get(owner, "/api/v1/salons/" + s.salonId() + "/clients").getBody());
        assertThat(list.at("/data/data/0/ratingCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("scope 10 (BOOK-78, REV-D10, REV-36): rate two guest visits -> the provider detail of ANOTHER booking "
            + "with that phone, the guest card and the list row all show the same 4.50 / 2; an unrated phone shows 0")
    void should_showPhoneRatingOnBookingDetail_when_anotherBookingOfThePhoneIsRated() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        UUID first = completedGuest(s, "LINK", phone);
        UUID second = completedGuest(s, "STAFF", phone);
        UUID upcoming = fx.guestBooking(s.masterId(), s.salonId(), "LINK", phone, "Іванна", "Коваль", "CONFIRMED",
                fx.at(5));
        UUID unrated = fx.guestBooking(s.masterId(), s.salonId(), "LINK", newPhone(), "Олена", "Бойко", "CONFIRMED",
                fx.at(6));
        String owner = fx.token(s.ownerEmail());
        rateOk(owner, first, 5, null);
        rateOk(owner, second, 4, null);

        JsonNode detail = objectMapper.readTree(fx.get(owner, "/api/v1/bookings/" + upcoming).getBody()).path("data");
        JsonNode unratedDetail =
                objectMapper.readTree(fx.get(owner, "/api/v1/bookings/" + unrated).getBody()).path("data");
        JsonNode card = objectMapper.readTree(fx.get(owner, guestCardUrl(s.salonId(), upcoming)).getBody())
                .path("data");
        JsonNode listRow = null;
        for (JsonNode row : objectMapper.readTree(
                fx.get(owner, "/api/v1/salons/" + s.salonId() + "/clients").getBody()).at("/data/data")) {
            if (upcoming.toString().equals(row.path("guestBookingId").asText())) {
                listRow = row;
            }
        }

        assertThat(detail.path("clientReviewCount").asInt()).isEqualTo(2);
        assertThat(detail.path("clientAvgRating").decimalValue()).isEqualByComparingTo("4.50");
        assertThat(card.path("ratingCount").asInt()).as("card = detail (REV-36)").isEqualTo(2);
        assertThat(card.path("ratingAvg").decimalValue()).isEqualByComparingTo("4.50");
        assertThat(listRow).as("the phone's list row (anchor = newest booking)").isNotNull();
        assertThat(listRow.path("ratingCount").asInt()).as("list = detail (REV-36)").isEqualTo(2);
        assertThat(listRow.path("ratingAvg").decimalValue()).isEqualByComparingTo("4.50");
        assertThat(unratedDetail.path("clientReviewCount").isInt()).as("a phone: 0, not null").isTrue();
        assertThat(unratedDetail.path("clientReviewCount").asInt()).isZero();
        assertThat(unratedDetail.path("clientAvgRating").isNull()).isTrue();
    }

    @Test
    @DisplayName("UserPhoneService: the rated guest's phone is verified by an account -> the row moves, the aggregate "
            + "is 1 and the guest card is gone (CLI-64)")
    void should_moveRatingAndDropGuestCard_when_accountVerifiesThePhone() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        UUID booking = completedGuest(s, "LINK", phone);
        rateOk(fx.token(s.ownerEmail()), booking, 5, "Чудова");
        String email = newEmail();
        UUID account = seedUser("CLIENT", email, true, null, null);
        String token = accessToken(email);

        verifyPhone(token, phone, null);

        Map<String, Object> row = reviewRow(booking);
        assertThat(row.get("subject_client_id")).isEqualTo(account);
        assertThat(row.get("subject_guest_phone")).isNull();
        assertThat(myRating(token).path("reviewCount").asInt()).isEqualTo(1);
        ResponseEntity<String> card = fx.get(fx.token(s.ownerEmail()), guestCardUrl(s.salonId(), booking));
        assertThat(card.getStatusCode()).as("the guest anchor is attached now").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("sweep: a verified CLIENT phone with rated, unattached rows -> the sweep attaches them and moves the rating")
    void should_moveRating_when_theSweepAttaches() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        UUID booking = completedGuest(s, "STAFF", phone);
        rateOk(fx.token(s.ownerEmail()), booking, 3, null);
        UUID account = seedUser("CLIENT", null, false, phone, null);

        attachService.sweep(200);

        assertThat(reviewRow(booking).get("subject_client_id")).isEqualTo(account);
        assertThat(reviewRow(booking).get("subject_guest_phone")).isNull();
        assertThat(myRating(accessToken(phone)).path("reviewCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("V269 CHECK: a raw insert with neither subject, or with both, is rejected")
    void should_rejectRawInsert_when_subjectIsNeitherOrBoth() {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        UUID client = fx.client("Ольга", "Б");
        UUID bothBooking = completedGuest(s, "LINK", phone);
        UUID neitherBooking = completedGuest(s, "LINK", newPhone());
        String insert = "INSERT INTO client_reviews (booking_id, subject_client_id, subject_guest_phone, "
                + "author_master_id, salon_id, rating) VALUES (?, ?, ?, ?, ?, 5)";

        assertThatThrownBy(() -> jdbcTemplate.update(insert, neitherBooking, null, null, s.masterId(), s.salonId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_client_reviews_one_subject");
        assertThatThrownBy(() -> jdbcTemplate.update(insert, bothBooking, client, phone, s.masterId(), s.salonId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_client_reviews_one_subject");
    }

    @Test
    @DisplayName("flag parity: for every provider role x booking kind, detail flag == list flag == POST outcome")
    void should_agreeOnFlagAndWrite_when_everyRoleMeetsEveryKind() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String adminEmail = fx.admin(s.salonId());
        ProviderClientFixtures.Independent im = fx.independent();
        record Actor(String name, String token, boolean salonSide, boolean hasMeList) {}
        List<Actor> actors = List.of(
                new Actor("owner", fx.token(s.ownerEmail()), true, true),
                new Actor("admin", fx.token(adminEmail), true, false),
                new Actor("salon master", fx.token(s.masterEmail()), true, true),
                new Actor("independent master", fx.token(im.email()), false, true));
        List<String> mismatches = new ArrayList<>();
        for (Actor actor : actors) {
            for (String kind : List.of("APP", "LINK", "STAFF", "STAFF_NO_PHONE")) {
                UUID masterId = actor.salonSide() ? s.masterId() : im.masterId();
                UUID salonId = actor.salonSide() ? s.salonId() : null;
                UUID booking = seedKind(kind, masterId, salonId);
                boolean detail = detailFlag(actor.token(), booking);
                Boolean list = actor.hasMeList() ? listFlag(actor.token(), booking) : null;
                boolean wrote = rate(actor.token(), booking, 5, null).getStatusCode() == HttpStatus.CREATED;
                boolean expected = !"STAFF_NO_PHONE".equals(kind);
                if (detail != expected || wrote != expected || (list != null && list != expected)) {
                    mismatches.add(actor.name() + "/" + kind + ": detail=" + detail + " list=" + list
                            + " wrote=" + wrote);
                }
            }
        }

        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("GST-51 race (deterministic latch sequencing, no sleep): a rating that passed its row-locking gate "
            + "makes the SKIP LOCKED sweep skip the booking; once it commits, the next sweep anonymises the booking "
            + "and deletes the rating -> zero phone-keyed rows left")
    void should_leaveNoPhoneKeyedRow_when_anonymisationSweepRacesTheRating() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        UUID booking = completedGuest(s, "STAFF", phone);
        UUID ownerId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class,
                s.ownerEmail());
        CountDownLatch gateHeld = new CountDownLatch(1);
        CountDownLatch sweepDone = new CountDownLatch(1);
        ExecutorService rater = Executors.newSingleThreadExecutor();
        try {
            Future<?> rating = rater.submit(() -> runAs(ownerId, "ROLE_SALON_OWNER", () ->
                    transactionTemplate.executeWithoutResult(tx -> {
                        // The guest arm's gate, exactly as ClientReviewService runs it: phone lock, then the row lock.
                        bookingRepository.acquireWalkInPhoneLock(phone);
                        try {
                            assertThat(bookingRepository.existsUnattachedGuestBooking(booking, phone)).isTrue();
                        } finally {
                            // Released even on failure, so the main thread reaches rating.get() and reports the
                            // assertion's real cause instead of a latch timeout.
                            gateHeld.countDown();
                        }
                        await(sweepDone);
                        clientReviewService.create(ownerId, new CreateClientReviewRequest(booking, 2, "Запізнилась"));
                    })));
            await(gateHeld);

            anonymisationService.anonymiseDue(Instant.now());
            Object anonymisedWhileRating = jdbcTemplate.queryForObject(
                    "SELECT guest_pii_anonymised_at FROM bookings WHERE id = ?", Object.class, booking);
            sweepDone.countDown();
            rating.get(30, TimeUnit.SECONDS);
            int phoneKeyedAfterRating = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM client_reviews WHERE booking_id = ? AND subject_guest_phone IS NOT NULL",
                    Integer.class, booking);
            anonymisationService.anonymiseDue(Instant.now());

            assertThat(anonymisedWhileRating).as("the sweep skipped the row-locked booking").isNull();
            assertThat(phoneKeyedAfterRating).as("the rating committed phone-keyed").isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM client_reviews "
                    + "WHERE subject_guest_phone = ?", Integer.class, phone)).as("REV-40: none left").isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT guest_pii_anonymised_at FROM bookings WHERE id = ?",
                    Object.class, booking)).as("the next sweep anonymised it").isNotNull();
        } finally {
            sweepDone.countDown();
            rater.shutdownNow();
        }
    }

    @Test
    @DisplayName("GST-51 race, sweep first (pg_stat_activity sequencing, no sleep): the rating loaded the booking "
            + "with its phone, then waited on the row lock the anonymisation held; once it commits the rating is the "
            + "NO_IDENTITY 400, never the attach 409, and no row is written")
    void should_reject400NoIdentity_when_bookingAnonymisedWhileRatingWaitsOnTheRowLock() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        UUID booking = completedGuest(s, "STAFF", phone);
        UUID ownerId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class,
                s.ownerEmail());
        ExecutorService rater = Executors.newSingleThreadExecutor();
        Connection holder = jdbcTemplate.getDataSource().getConnection();
        try {
            // The sweep's write, held uncommitted: the anonymised row and its row lock.
            holder.setAutoCommit(false);
            try (PreparedStatement anonymise = holder.prepareStatement(ANONYMISE_SQL)) {
                anonymise.setObject(1, booking);
                assertThat(anonymise.executeUpdate()).isOne();
            }
            Future<Throwable> rating = rater.submit(() -> catchThrowable(() -> runAs(ownerId, "ROLE_SALON_OWNER",
                    () -> clientReviewService.create(ownerId, new CreateClientReviewRequest(booking, 2, null)))));
            boolean waitedOnRowLock = awaitRowLockWaiter("FOR UPDATE OF b");
            holder.commit();
            Throwable failure = rating.get(30, TimeUnit.SECONDS);

            assertThat(waitedOnRowLock).as("the rating blocked in the re-read, past its gate").isTrue();
            assertThat(failure).isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                    .isNotInstanceOf(ConflictException.class)
                    .hasMessage("Cannot review this booking — it has no client identity");
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?",
                    Integer.class, booking)).isZero();
        } finally {
            if (!holder.isClosed()) {
                holder.rollback();
                holder.close();
            }
            rater.shutdownNow();
        }
    }

    /** Mirrors the GST-51 sweep's per-row anonymisation (same columns as {@code seedKind}'s STAFF_NO_PHONE arm). */
    private static final String ANONYMISE_SQL = "UPDATE bookings SET guest_name = 'Гість', guest_surname = NULL, "
            + "guest_phone = NULL, cancel_token_hash = NULL, cancel_token_sealed = NULL, "
            + "guest_pii_anonymised_at = NOW() WHERE id = ?";

    private boolean awaitRowLockWaiter(String queryFragment) {
        try {
            Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20)).until(() -> {
                Long waiters = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                                + "AND wait_event_type = 'Lock' AND pid <> pg_backend_pid() AND query ILIKE ?",
                        Long.class, "%" + queryFragment + "%");
                return waiters != null && waiters >= 1;
            });
            return true;
        } catch (ConditionTimeoutException e) {
            return false;
        }
    }

    private static void runAs(UUID userId, String authority, Runnable body) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority(authority)));
        auth.setDetails(userId);
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            body.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch released").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private UUID seedKind(String kind, UUID masterId, UUID salonId) {
        return switch (kind) {
            case "APP" -> fx.booking(masterId, salonId, fx.client("Клієнтка", "А"), "COMPLETED", fx.at(-4));
            case "LINK" -> fx.guestBooking(masterId, salonId, "LINK", newPhone(), "Гостя", "Б", "COMPLETED",
                    fx.at(-4));
            case "STAFF" -> fx.guestBooking(masterId, salonId, "STAFF", newPhone(), "Гостя", "В", "COMPLETED",
                    fx.at(-4));
            default -> {
                // A walk-in whose phone is gone (anonymised, GST-51; the code requires a phone at create, D2): no
                // identity to rate.
                UUID id = fx.guestBooking(masterId, salonId, "STAFF", newPhone(), "Гостя", "Г", "COMPLETED",
                        fx.at(-4));
                jdbcTemplate.update("UPDATE bookings SET guest_name = 'Гість', guest_surname = NULL, guest_phone = NULL, "
                        + "cancel_token_hash = NULL, cancel_token_sealed = NULL, guest_pii_anonymised_at = NOW() "
                        + "WHERE id = ?", id);
                yield id;
            }
        };
    }

    private boolean detailFlag(String token, UUID bookingId) throws Exception {
        ResponseEntity<String> resp = fx.get(token, "/api/v1/bookings/" + bookingId);
        assertThat(resp.getStatusCode()).as("detail %s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).at("/data/providerCanReviewClient").asBoolean();
    }

    private Boolean listFlag(String token, UUID bookingId) throws Exception {
        ResponseEntity<String> resp = fx.get(token, "/api/v1/bookings/me?size=100");
        assertThat(resp.getStatusCode()).as("list %s", resp.getBody()).isEqualTo(HttpStatus.OK);
        for (JsonNode row : objectMapper.readTree(resp.getBody()).at("/data/data")) {
            if (bookingId.toString().equals(row.path("id").asText())) {
                return row.path("providerCanReviewClient").asBoolean();
            }
        }
        throw new AssertionError("booking " + bookingId + " missing from /bookings/me");
    }
}

package com.beautica.client.provider;

import com.beautica.booking.enums.AttachReason;
import com.beautica.booking.retention.GuestPiiAnonymisationService;
import com.beautica.client.service.ClientPhoneAttachService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B546 (REV-39, REV-40, REV-D11) — one test per CLI-62 path: a phone-keyed rating follows its booking to the account
 * (registration, add phone, phone change), with its comment, and the account aggregate recalculates; anonymised and
 * detached bookings' ratings never move; a booking attached at create is rated account-keyed.
 */
@DisplayName("Guest ratings move to the account on attach — B546 REV-D11")
class ClientReviewAttachIT extends AbstractClientRatingSubjectIT {

    @Autowired private ClientPhoneAttachService attachService;
    @Autowired private GuestPiiAnonymisationService anonymisationService;
    @Autowired private TransactionTemplate transactionTemplate;

    private void assertMovedTo(UUID bookingId, UUID account, String comment) {
        Map<String, Object> row = reviewRow(bookingId);
        assertThat(row.get("subject_client_id")).as("moved to the account").isEqualTo(account);
        assertThat(row.get("subject_guest_phone")).as("phone cleared").isNull();
        assertThat(row.get("comment")).as("comment moves with the row").isEqualTo(comment);
    }

    /** «Мій рейтинг»: number, count and distribution only — never the comment, author or review id (REV-13). */
    private void assertMyRating(String token, int count, String avg) throws Exception {
        JsonNode rating = myRating(token);
        assertThat(rating.path("reviewCount").asInt()).isEqualTo(count);
        assertThat(rating.path("avgRating").decimalValue()).isEqualByComparingTo(avg);
        assertThat(rating.has("comment")).isFalse();
        assertThat(rating.toString()).doesNotContain("authorMasterId").doesNotContain("\"id\"")
                .doesNotContain("Пунктуальна").doesNotContain("Спізнилась");
    }

    @Test
    @DisplayName("registration: a LINK guest and a STAFF walk-in rated on P, P registers -> both rows move with their "
            + "comments; /users/me/rating = 2, avg 4.5, no comment / author / id")
    void should_moveBothRatings_when_thePhoneRegisters() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        UUID link = completedGuest(s, "LINK", phone);
        UUID walkIn = completedGuest(s, "STAFF", phone);
        String owner = fx.token(s.ownerEmail());
        rateOk(owner, link, 5, "Пунктуальна");
        rateOk(owner, walkIn, 4, "Спізнилась");

        register(phone);

        UUID account = userIdOfPhone(phone);
        assertMovedTo(link, account, "Пунктуальна");
        assertMovedTo(walkIn, account, "Спізнилась");
        assertMyRating(accessToken(phone), 2, "4.5");
    }

    @Test
    @DisplayName("existing account adds P later (UserPhoneService) -> the rated rows of P move; aggregate = 2")
    void should_moveRatings_when_anExistingAccountAddsThePhone() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        UUID link = completedGuest(s, "LINK", phone);
        UUID walkIn = completedGuest(s, "STAFF", phone);
        String owner = fx.token(s.ownerEmail());
        rateOk(owner, link, 5, "Пунктуальна");
        rateOk(owner, walkIn, 3, null);
        String email = newEmail();
        UUID account = seedUser("CLIENT", email, true, null, null);
        String token = accessToken(email);

        verifyPhone(token, phone, null);

        assertMovedTo(link, account, "Пунктуальна");
        assertMovedTo(walkIn, account, null);
        assertMyRating(token, 2, "4");
    }

    @Test
    @DisplayName("phone change P -> Q: the moved rows stay with the account; a walk-in on P created after the change "
            + "and rated stays phone-keyed")
    void should_keepMovedRowsAndLeaveNewOldPhoneWalkInPhoneKeyed_when_thePhoneChanges() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phoneP = newPhone();
        String phoneQ = newPhone();
        UUID account = seedUser("CLIENT", null, false, phoneP, null);
        UUID before = completedGuest(s, "STAFF", phoneP);
        String owner = fx.token(s.ownerEmail());
        rateOk(owner, before, 5, null);
        transactionTemplate.executeWithoutResult(st -> attachService.attachByPhone(phoneP, AttachReason.PHONE_VERIFIED));
        assertMovedTo(before, account, null);
        String token = accessToken(phoneP);

        verifyPhone(token, phoneQ, PASSWORD);
        UUID after = completedGuest(s, "STAFF", phoneP);
        rateOk(owner, after, 2, null);
        attachService.sweep(200);

        assertMovedTo(before, account, null);
        assertThat(reviewRow(after).get("subject_guest_phone")).as("old-phone walk-in stays phone-keyed")
                .isEqualTo(phoneP);
        assertThat(reviewRow(after).get("subject_client_id")).isNull();
        assertMyRating(token, 1, "5");
    }

    @Test
    @DisplayName("anonymised (GST-51): the phone-keyed rating is deleted, never moved; detached (CLI-66): the rating of "
            + "a detached booking is never moved to a new account")
    void should_deleteAnonymisedRatingAndNeverMoveDetachedOne_when_piiIsErasedOrAccountDeleted() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String owner = fx.token(s.ownerEmail());
        String erased = newPhone();
        UUID anonymised = completedGuest(s, "LINK", erased);
        rateOk(owner, anonymised, 1, "Не прийшла");

        anonymisationService.eraseByPhone(erased, "TEST-546");

        assertThat(reviewCount(anonymised)).as("rating dies with the guest PII (REV-40)").isZero();

        String phone = newPhone();
        UUID detached = completedGuest(s, "LINK", phone);
        rateOk(owner, detached, 4, null);
        JsonNode auth = json(register(phone)).at("/data/auth");
        assertMovedTo(detached, userIdOfPhone(phone), null);
        ResponseEntity<Void> deleted = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new org.springframework.http.HttpEntity<>(bearer(auth.at("/accessToken").asText())), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(reviewCount(detached)).as("the account's ratings die with the account (CLI-66)").isZero();
        elapseResendCooldown(phone);
        ResponseEntity<String> again = postFrom("10.209." + (System.nanoTime() % 250) + ".9",
                "/api/v1/auth/register", consent(new java.util.HashMap<>(Map.of("registrationTicket", ticketFor(phone),
                        "password", PASSWORD, "firstName", "Олена", "role", "CLIENT"))));
        assertThat(again.getStatusCode()).as(again.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM client_reviews WHERE subject_client_id = ?",
                Integer.class, userIdOfPhone(phone))).as("nothing moves to the new account").isZero();
    }

    @Test
    @DisplayName("attach at create (GST-83): a walk-in on an account's verified phone is rated account-keyed, phone null")
    void should_writeAccountKeyed_when_theBookingWasAttachedAtCreate() throws Exception {
        ProviderClientFixtures.Salon s = fx.salon();
        String phone = newPhone();
        UUID account = seedUser("CLIENT", null, false, phone, null);
        UUID walkIn = completedGuest(s, "STAFF", phone);
        // The create path stamps BOOKING-reason attach before the first persist; mirror its committed row.
        jdbcTemplate.update("UPDATE bookings SET client_id = ?, client_link_reason = 'BOOKING', "
                + "client_linked_at = NOW(), guest_phone = NULL, cancel_token_hash = NULL, cancel_token_sealed = NULL "
                + "WHERE id = ?", account, walkIn);

        rateOk(fx.token(s.ownerEmail()), walkIn, 5, null);

        assertMovedTo(walkIn, account, null);
        assertMyRating(accessToken(phone), 1, "5");
    }
}

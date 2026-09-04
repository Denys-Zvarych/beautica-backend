package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.review.dto.SalonReviewResponse;
import com.beautica.review.repository.ReviewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-DB contract for V157 — the schema that makes a staff {@code users} row deletable
 * (phase 294, {@code docs/backend-phases/phase-294-detachable-master-and-staff-delete-fk-relaxation.md}).
 *
 * <p><b>This phase ships no behaviour.</b> Nothing in the application detaches a master or deletes
 * a user (D6); every assertion below drives the schema directly through raw SQL, exactly as phase
 * 295 will. What is being pinned is that the two states — ATTACHED and DETACHED — are the only two
 * the database will accept, that the three relaxed foreign keys now SET NULL instead of refusing,
 * and that the read paths a detached row is visible from still render a provider name instead of
 * NPEing.
 *
 * <p><b>The two mandated mutation checks</b> (phase 294 § Mutation check):
 * <ul>
 *   <li>Remove the {@code detached_first_name IS NOT NULL} arm of
 *       {@code chk_masters_detachment_coherent} →
 *       {@link #should_rejectDetachWithoutName_when_userIdIsNulled()}'s SECOND statement goes red.
 *       Its first statement (no {@code detached_at} either) is deliberately NOT the mutation
 *       detector — it would still fail on the {@code detached_at IS NOT NULL} arm and mask the
 *       removal.</li>
 *   <li>Revert {@code Master#displayFirstName()} to {@code user.getFirstName()} →
 *       {@link #should_renderProviderName_when_bookingsMasterIsDetached()} goes red with a 500
 *       (NullPointerException inside {@code BookingDetailResponse#from}), not a silent pass.</li>
 * </ul>
 *
 * <p><b>Three further mutation checks</b> added with cases 14–16 (2026-09-04 QA pass), each proven
 * to go red on exactly the named case and nothing else:
 * <ul>
 *   <li>Restore the INNER {@code JOIN m.user} in {@code hydrateClientBookingDetails} AND
 *       {@code findMyReviews} → cases 14 + 15 red.</li>
 *   <li>Drop the {@code COALESCE(mu.*, m.detached*)} back to a bare {@code mu.*} in both →
 *       cases 14 + 15 red.</li>
 *   <li>Relax {@code chk_masters_detachment_coherent}'s first arm to a bare
 *       {@code user_id IS NOT NULL} → case 16 red.</li>
 * </ul>
 */
@DisplayName("V157 — detachable master + staff-delete FK relaxation (phase 294)")
class MasterDetachmentContractIT extends AbstractIntegrationTest {

    private static final String DETACHED_FIRST = "Знята";
    private static final String DETACHED_LAST = "Майстриня";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MasterRepository masterRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private BookingTestFixtures fixtures;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        tx = new TransactionTemplate(transactionManager);
    }

    // ── case 1 — the ATTACHED state is unchanged ────────────────────────────────────────────────

    @Test
    @DisplayName("case 1 — an attached master reads displayFirstName()/displayLastName() straight "
            + "off its users row, and isDetached() is false")
    void should_readNameFromUsersRow_when_masterIsAttached() {
        UUID masterId = fixtures.createIndependentMaster(email("attached"));
        jdbcTemplate.update("UPDATE users SET first_name = ?, last_name = ? WHERE id = ?",
                "Олена", "Живий", userIdOf(masterId));

        Master master = loadMaster(masterId);

        assertThat(master.isDetached()).isFalse();
        assertThat(master.displayFirstName()).isEqualTo("Олена");
        assertThat(master.displayLastName()).isEqualTo("Живий");
    }

    // ── case 2 — D4: a half-detached row is UNWRITABLE ──────────────────────────────────────────

    @Test
    @DisplayName("case 2 — nulling user_id without a coherent snapshot violates "
            + "chk_masters_detachment_coherent (D4)")
    void should_rejectDetachWithoutName_when_userIdIsNulled() {
        UUID masterId = fixtures.createIndependentMaster(email("incoherent"));

        // (a) neither arm satisfied — no detached_at, no name.
        assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE masters SET user_id = NULL WHERE id = ?", masterId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_masters_detachment_coherent");

        // (b) THE MUTATION DETECTOR. detached_at IS set, so the only arm left to fail is
        // `detached_first_name IS NOT NULL`. Removing that arm from the CHECK makes this UPDATE
        // succeed — a nameless provider on a client's own past receipt, which is precisely the
        // state D4 exists to make unwritable.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE masters SET user_id = NULL, detached_at = NOW() WHERE id = ?", masterId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_masters_detachment_coherent");

        // The row is untouched by either rejected statement.
        assertThat(userIdOf(masterId)).isNotNull();
    }

    // ── case 3 — the DETACHED state is representable and reads back ─────────────────────────────

    @Test
    @DisplayName("case 3 — a coherent detached row persists and reads its snapshot name through "
            + "the D3 accessors")
    void should_readSnapshotName_when_masterIsDetached() {
        UUID masterId = fixtures.createIndependentMaster(email("detached"));

        detach(masterId);

        Master master = loadMaster(masterId);
        assertThat(master.isDetached()).isTrue();
        assertThat(master.displayFirstName()).isEqualTo(DETACHED_FIRST);
        assertThat(master.displayLastName()).isEqualTo(DETACHED_LAST);
    }

    // ── case 4 — D1: N detached rows coexist under UNIQUE (user_id) ─────────────────────────────

    @Test
    @DisplayName("case 4 — two detached masters coexist: Postgres allows unlimited NULLs under the "
            + "UNIQUE (user_id) constraint, so no partial index is needed (D1)")
    void should_allowTwoDetachedMasters_when_uniqueUserIdConstraintApplies() {
        UUID firstMasterId = fixtures.createIndependentMaster(email("coexist-a"));
        UUID secondMasterId = fixtures.createIndependentMaster(email("coexist-b"));
        detach(firstMasterId);

        assertThatCode(() -> detach(secondMasterId)).doesNotThrowAnyException();

        Integer detachedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM masters WHERE user_id IS NULL AND id IN (?, ?)",
                Integer.class, firstMasterId, secondMasterId);
        assertThat(detachedCount).isEqualTo(2);
    }

    // ── case 5 — D1: deleting the users row detaches instead of being refused ───────────────────

    /**
     * <b>D1 and D4 constrain each other — read this before writing phase 295.</b> The phase doc's
     * case 5 asks for a bare {@code DELETE FROM users} that "succeeds and leaves
     * {@code masters.user_id = NULL}", i.e. for the FK's {@code ON DELETE SET NULL} to perform the
     * detachment on its own. <b>That is not representable under D4.</b> The SET NULL writes exactly
     * one column, and the resulting row satisfies neither arm of
     * {@code chk_masters_detachment_coherent} ({@code detached_at} is still NULL, and it cannot
     * have been pre-set: while the master is attached, the first arm requires
     * {@code detached_at IS NULL}). Postgres cannot defer a CHECK constraint either — only UNIQUE,
     * PK, FK and EXCLUDE are deferrable — so no in-transaction ordering rescues it.
     *
     * <p>Both decisions are implemented exactly as written; what this test pins is the one order
     * that IS representable, and which phase 295 must therefore use:
     * <ol>
     *   <li>ONE {@code UPDATE masters} writing the snapshot and nulling {@code user_id} together
     *       (the whole-row CHECK is satisfied), then</li>
     *   <li>{@code DELETE FROM users}, now unblocked because nothing references the row.</li>
     * </ol>
     * Assertion (a) below pins what the relaxed FK actually bought: the delete is no longer refused
     * by {@code masters.user_id}'s missing on-delete clause (the V4 constraint that blocked it
     * ALWAYS) — it is refused, if at all, by the coherence rule, which the caller controls.
     */
    @Test
    @DisplayName("case 5 — the users row is deletable once the master is detached: the V4 FK no "
            + "longer refuses it, and masters.user_id is left NULL (D1, bounded by D4)")
    void should_setMasterUserIdNull_when_staffUserIsDeleted() {
        UUID masterId = fixtures.createIndependentMaster(email("hard-delete"));
        UUID masterUserId = userIdOf(masterId);

        // (a) With the master still ATTACHED, the delete is rejected by the COHERENCE rule, not by
        // the foreign key — proof that D1 landed. Before V157 this raised
        // "violates foreign key constraint ... on table masters".
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", masterUserId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_masters_detachment_coherent")
                .hasMessageNotContaining("foreign key constraint");

        // (b) The representable order: detach in one statement, then delete.
        detach(masterId);
        int deleted = jdbcTemplate.update("DELETE FROM users WHERE id = ?", masterUserId);

        assertThat(deleted).isEqualTo(1);
        assertThat(userIdOf(masterId)).isNull();
        assertThat(loadMaster(masterId).displayFirstName()).isEqualTo(DETACHED_FIRST);
    }

    // ── case 6 — D5: created_by_user_id RESTRICT → SET NULL, on BOTH tables ─────────────────────

    @Test
    @DisplayName("case 6 — deleting the staff user who created a walk-in succeeds; "
            + "bookings.created_by_user_id and appointments.created_by_user_id go NULL and the "
            + "rows themselves are untouched (D5)")
    void should_nullCreatedByUserId_when_creatingStaffUserIsDeleted() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("mdc-creator", 1);
        UUID bookingId = leadBookingId(visit.id());
        // A user with NO masters row of their own, so this case isolates the two
        // created_by_user_id FKs from masters.user_id (case 5's subject).
        UUID staffUserId = fixtures.createUser(email("walkin-creator"), "SALON_ADMIN", null);
        jdbcTemplate.update("UPDATE bookings SET created_by_user_id = ? WHERE id = ?",
                staffUserId, bookingId);
        jdbcTemplate.update("UPDATE appointments SET created_by_user_id = ? WHERE id = ?",
                staffUserId, visit.id());

        int deleted = jdbcTemplate.update("DELETE FROM users WHERE id = ?", staffUserId);

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_by_user_id FROM bookings WHERE id = ?", UUID.class, bookingId))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_by_user_id FROM appointments WHERE id = ?", UUID.class, visit.id()))
                .isNull();
        // The booking row itself survives the creator's deletion — SET NULL, not CASCADE.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                .isEqualTo("CONFIRMED");
    }

    // ── case 7 — D3: the client's own history does not go anonymous ─────────────────────────────

    @Test
    @DisplayName("case 7 — GET /bookings/{id} still renders a provider first/last name after the "
            + "booking's master is detached; the client's history does not go anonymous (D3)")
    void should_renderProviderName_when_bookingsMasterIsDetached() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("mdc-detached", 1);
        UUID bookingId = leadBookingId(visit.id());

        detach(visit.masterId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(visit.clientToken())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("masterFirstName").asText()).isEqualTo(DETACHED_FIRST);
        assertThat(data.path("masterLastName").asText()).isEqualTo(DETACHED_LAST);
    }

    // ── case 8 — the rating aggregate is detachment-agnostic ────────────────────────────────────

    @Test
    @DisplayName("case 8 — a review whose master is detached still counts toward "
            + "masters.avg_rating / review_count (the aggregate keys on reviews.master_id)")
    void should_keepCountingReview_when_reviewedMasterIsDetached() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("mdc-rating", 1);
        UUID bookingId = leadBookingId(visit.id());
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);
        insertReview(bookingId, visit.clientId(), visit.masterId(), 4);
        tx.executeWithoutResult(status -> reviewRepository.recalculateMasterRating(visit.masterId()));
        // Fixture check: the aggregate MOVED off its 0.00/0 default before the detach, so the
        // post-detach assertion cannot pass vacuously.
        assertThat(reviewCountOf(visit.masterId())).isEqualTo(1);

        detach(visit.masterId());
        tx.executeWithoutResult(status -> reviewRepository.recalculateMasterRating(visit.masterId()));

        assertThat(reviewCountOf(visit.masterId())).isEqualTo(1);
        assertThat(avgRatingOf(visit.masterId())).isEqualByComparingTo(new BigDecimal("4.00"));
    }

    // ── case 9 — 2026-09 audit finding 1: the PUBLIC salon review list masks the erased name ────

    /**
     * <b>RED before the fix</b> — {@code SalonReviewResponse#from} used to render
     * {@code Master#displayFirstName()}/{@code displayLastName()} unconditionally, so this
     * assertion read {@code "Знята"}/{@code "Майстриня"} instead of the neutral label: the erased
     * person's name, published to an ANONYMOUS caller on a {@code permitAll()}, {@code
     * reviews-by-salon}-cached endpoint, readable for as long as the review exists.
     *
     * <p>The 2026-09-04 product decision (a deleted master's name survives on a client's own past
     * booking — pinned by case 7 above) is scoped to THAT receipt. It does not extend here; case 7
     * and this case must therefore always disagree about what the same detached master is called,
     * and neither may be "aligned" onto the other.
     *
     * <p>The review itself is still LISTED — masking is not dropping. A future INNER
     * {@code JOIN FETCH m.user} in {@code findByIdsWithGraphForSalonReviews} would drop the row and
     * fail the {@code totalElements}/first-row assertions below, so this case doubles as that
     * query's LEFT-fetch gate.
     */
    @Test
    @DisplayName("case 9 — an anonymous GET /salons/{id}/reviews renders a DETACHED master under "
            + "the neutral label, never the detached_* name snapshot (audit finding 1)")
    void should_maskProviderName_when_anonymousReadsSalonReviewsOfDetachedMaster() throws Exception {
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(email("public-salon-owner"));
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("mdc-public-rev", 1);
        UUID bookingId = leadBookingId(visit.id());
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);
        insertReview(bookingId, visit.clientId(), visit.masterId(), salon.salonId(), 5);

        detach(visit.masterId());

        // No Authorization header at all — this is the unauthenticated public path.
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/salons/" + salon.salonId() + "/reviews", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong())
                .as("the review must still be listed — masking the name is not dropping the row")
                .isEqualTo(1L);
        JsonNode row = page.path("data").path(0);
        assertThat(row.path("masterFirstName").asText())
                .isEqualTo(SalonReviewResponse.DETACHED_MASTER_LABEL);
        assertThat(row.path("masterLastName").isNull())
                .as("no surname is published for a detached master")
                .isTrue();
        assertThat(response.getBody())
                .as("neither half of the erased person's name may appear anywhere in the payload")
                .doesNotContain(DETACHED_FIRST)
                .doesNotContain(DETACHED_LAST);
    }

    // ── case 10 — 2026-09 audit finding 2: the all-items visit guard is not defeatable ──────────

    /**
     * <b>RED before the fix</b> — {@code BookingRepository#findAllCompletionAccessByAppointmentId}
     * INNER-joined {@code bm.user}, so the foreign DETACHED master's item simply VANISHED from the
     * projection. {@code access.isEmpty()} stayed false (the actor's own item survived) and
     * {@code allMatch} then passed over the SURVIVING SUBSET — the whole visit was declined, 204,
     * including the item whose master the actor has no authority over whatsoever. That is the
     * method's own javadoc contract inverted: it fetches every row precisely so that a single
     * disagreeing item denies the call.
     *
     * <p>The mixed-master visit is built by raw SQL on purpose. It is not reachable through any
     * writer today ({@code VisitPlanner.planChainedItems} resolves every item off ONE master) and
     * nothing in the schema forbids it — which is exactly why the projection must not assume it
     * away. Same rationale the repository javadoc gives for refusing the old {@code Limit.of(1)}.
     */
    @Test
    @DisplayName("case 10 — a whole-visit decline is REFUSED when one item belongs to a foreign "
            + "DETACHED master, instead of silently authorizing over the surviving items "
            + "(audit finding 2)")
    void should_return403_when_visitContainsDetachedForeignMasterItem() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("mdc-mixed", 2);
        List<UUID> itemIds = itemIdsOf(visit.id());
        assertThat(itemIds).as("fixture must produce a two-item visit").hasSize(2);
        UUID foreignMasterId = fixtures.createIndependentMaster(email("foreign-detached"));
        detach(foreignMasterId);
        jdbcTemplate.update(
                "UPDATE bookings SET master_id = ? WHERE id = ?", foreignMasterId, itemIds.get(1));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/appointments/" + visit.id() + "/decline", HttpMethod.PATCH,
                new HttpEntity<>(fixtures.bearerHeaders(visit.masterToken())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Fail-closed means nothing moved — not even the item the actor DOES own.
        assertThat(statusOfBooking(itemIds.get(0))).isEqualTo("CONFIRMED");
        assertThat(statusOfBooking(itemIds.get(1))).isEqualTo("CONFIRMED");
    }

    // ── case 11 — 2026-09 audit finding 3: @PreAuthorize denies, never 500s ─────────────────────

    /**
     * <b>RED before the fix</b> — {@code AuthorizationService#canManageMaster} did
     * {@code m.getUser().getId().equals(actorId)} with no null guard on a master loaded through
     * {@code findByIdWithUserAndSalon} ({@code LEFT JOIN FETCH m.user}, so a detached row really is
     * returned). The NPE was raised INSIDE the {@code @PreAuthorize} SpEL and surfaced as 500 —
     * which also leaks that something exists behind that id. Phase 294 guarded the two
     * {@code enforce*} twins and missed these two SpEL ones.
     */
    @Test
    @DisplayName("case 11 — a @PreAuthorize SpEL predicate aimed at a DETACHED master DENIES (403) "
            + "instead of NPEing into a 500 (audit finding 3)")
    void should_return403_not500_when_preAuthorizeTargetsDetachedMaster() throws Exception {
        UUID detachedMasterId = fixtures.createIndependentMaster(email("preauth-target"));
        detach(detachedMasterId);
        String actorEmail = email("preauth-actor");
        fixtures.createIndependentMaster(actorEmail);
        String actorToken = fixtures.tokenFor(actorEmail);

        // DELETE /masters/{id} is gated by @authz.canManageMaster — the SpEL twin phase 294 missed.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/masters/" + detachedMasterId, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(actorToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── case 12 — 2026-09 audit finding 4: the public master profile is gone, not half-rendered ──

    /**
     * <b>RED before the fix</b> — {@code MasterService#getMasterDetail} read
     * {@code master.getUser().getCityId()} unguarded, and {@code MasterDetailResponse#from} reads a
     * dozen more columns off the same null association. {@code GET /api/v1/masters/&#123;id&#125;}
     * is {@code permitAll()} and its query carries no {@code isActive} predicate, so this was an
     * anonymous, unauthenticated 500.
     *
     * <p>404 — NOT a name-masked partial profile. Rendering {@code displayFirstName()} here would
     * publish the erased person's public profile page, the opposite of what the deletion was for; a
     * detached master is {@code is_active = false} and already invisible to search, the roster and
     * the catalogue, so the profile endpoint must agree with them. It is also the SAME response a
     * never-existent id gets, so no existence oracle is introduced.
     */
    @Test
    @DisplayName("case 12 — an anonymous GET /masters/{id} for a DETACHED master is 404, not a 500 "
            + "and not a published profile (audit finding 4)")
    void should_return404_when_anonymousReadsDetachedMasterProfile() {
        UUID masterId = fixtures.createIndependentMaster(email("public-profile"));
        detach(masterId);

        // No Authorization header — SecurityConfig permitAll()s this path.
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/masters/" + masterId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .doesNotContain(DETACHED_FIRST)
                .doesNotContain(DETACHED_LAST);
    }

    // ── case 13 — cycle-2 audit finding 5: the salon OWNER keeps the completion path ────────────

    /**
     * <b>RED before the fix</b> — {@code BookingRepository#findCompletionAccessById} INNER-joined
     * {@code bm.user}, so a booking whose master is DETACHED produced no projection row at all.
     * {@code Optional.empty()} met {@code .orElse(false)} in all four SpEL predicates
     * ({@code canCompleteBooking}, {@code canCancelBooking}, {@code canRescheduleBooking},
     * {@code canReviewClient}) and the SALON OWNER got a 403 on their OWN salon's booking. That is
     * fail-closed, not a bypass — but it denied the wrong party: whether the salon owner may close
     * out a visit has nothing to do with whether the master's {@code users} row still exists.
     *
     * <p>Relaxing the join is safe precisely because the salon arm of
     * {@code AuthorizationService#hasProviderAuthorityOverRow} never reads {@code masterUserId} — a
     * detached SALON master keeps a non-null {@code bs.id}, so the owner arm fires on {@code salonId}
     * alone, while the independent-master arm still compares a null against a non-null actor id and
     * fails closed (case 11's shape).
     *
     * <p>{@code PATCH /bookings/&#123;id&#125;/complete} is the endpoint under test because it is the
     * one whose {@code @PreAuthorize} is the SOLE authorization gate carrying this projection; the
     * service-layer twin ({@code enforceCanCompleteBooking}, entity-based) already handled the
     * detached case. A 204 therefore proves the SpEL predicate, not just the service guard, admitted
     * the owner.
     */
    @Test
    @DisplayName("case 13 — a SALON OWNER may still complete a booking whose master has been "
            + "DETACHED; the completion projection must not drop the row (audit cycle-2 finding 5)")
    void should_allowSalonOwnerToComplete_when_bookingsMasterIsDetached() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("mdc-owner-complete", 1);
        UUID bookingId = leadBookingId(visit.id());
        BookingFixture booking = reparentOntoSalonMaster(bookingId, email("complete-owner"));
        // Negative control, evaluated FIRST so it cannot observe a completed booking: a DIFFERENT
        // salon's owner is still refused. Without it a 204 below would also be satisfied by
        // authorization being off altogether, which is the other way this case could go vacuous.
        String foreignOwnerToken =
                fixtures.tokenFor(fixtures.createSalon(email("complete-foreign-owner")).ownerEmail());

        detach(booking.masterId());

        assertThat(completeAs(bookingId, foreignOwnerToken))
                .as("a foreign salon owner must still be denied — proves the 204 below is authorization "
                        + "succeeding, not authorization being absent")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(completeAs(bookingId, booking.ownerToken()))
                .as("the detached master's booking must still authorize its OWN salon owner — a 403 here "
                        + "is the INNER JOIN bm.user coming back in findCompletionAccessById")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(statusOfBooking(bookingId)).isEqualTo("COMPLETED");
    }

    // ── case 14 — QA gap: the CLIENT's own booking LIST projection, not just the detail read ─────

    /**
     * Case 7 pins the single-booking ENTITY path ({@code BookingDetailResponse#from(Booking, …)}).
     * This case pins the OTHER renderer of the same DTO — the ID-then-hydrate LIST path backing
     * {@code GET /bookings/me}, which builds {@link com.beautica.booking.dto.BookingDetailResponse}
     * from {@code BookingRepository#hydrateClientBookingDetails}'s JPQL constructor expression
     * instead. The two must agree, and until this case existed nothing proved they did: the audit's
     * finding-6 fix ({@code LEFT JOIN m.user mu} + {@code COALESCE(mu.firstName,
     * m.detachedFirstName)}) shipped with no test at all.
     *
     * <p><b>Two independent regressions are caught here, which is why both assertions are
     * mandatory.</b>
     * <ul>
     *   <li>Restoring the INNER {@code JOIN m.user mu} does NOT make the endpoint fail — it makes it
     *       LIE. The ID page still counts the booking ({@code totalElements = 1}, that query never
     *       joins {@code m.user}) while the hydrate returns nothing, so the client's own past
     *       booking silently vanishes from a page that still claims to contain it. Only the
     *       content-size-vs-totalElements assertion sees that; a name assertion alone would read an
     *       empty list and pass vacuously on index 0.</li>
     *   <li>Dropping the {@code COALESCE} back to a bare {@code mu.firstName} keeps the row but
     *       renders a NAMELESS provider — the exact state D4 exists to make unwritable at the DB
     *       level, re-introduced one layer up.</li>
     * </ul>
     */
    @Test
    @DisplayName("case 14 — GET /bookings/me still LISTS the client's own booking after its master "
            + "is DETACHED, with the snapshot name; the page does not desync (audit finding 6)")
    void should_listOwnBookingWithSnapshotName_when_masterIsDetached() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("mdc-my-list", 1);
        UUID bookingId = leadBookingId(visit.id());

        detach(visit.masterId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/bookings/me", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(visit.clientToken())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong())
                .as("the client's own booking must still be counted")
                .isEqualTo(1L);
        JsonNode rows = page.path("data");
        assertThat(rows.size())
                .as("content must not desync from totalElements — an INNER JOIN m.user drops the "
                        + "detached master's row here while the ID page still counts it")
                .isEqualTo(page.path("totalElements").asInt());
        JsonNode row = rows.path(0);
        assertThat(row.path("id").asText())
                .as("the row present must be the booking under test")
                .isEqualTo(bookingId.toString());
        assertThat(row.path("masterFirstName").asText())
                .as("the LIST projection must COALESCE onto the V157 snapshot, exactly as the entity "
                        + "path's displayFirstName() does (case 7)")
                .isEqualTo(DETACHED_FIRST);
        assertThat(row.path("masterLastName").asText()).isEqualTo(DETACHED_LAST);
    }

    // ── case 15 — QA gap: the client's OWN authored review survives the detach ───────────────────

    /**
     * The mirror of case 14 on the review side: {@code ReviewRepository#findMyReviews} backs
     * {@code GET /reviews/me} and carried the identical INNER-join defect, with a worse consequence
     * — the row that disappears is the client's own <i>writing</i>, while the count query (which
     * never joins {@code m.user}) keeps counting it.
     *
     * <p>This case and case 9 are the deliberate pair the 2026-09-04 product decision produces, and
     * they must never be "aligned": the client's OWN receipt names the provider from the snapshot
     * (here), while the ANONYMOUS public salon listing masks it (case 9). A regression that unified
     * them in either direction would flip exactly one of these two tests.
     */
    @Test
    @DisplayName("case 15 — GET /reviews/me still lists the client's OWN authored review after the "
            + "reviewed master is DETACHED, naming the provider from the snapshot (audit finding 6)")
    void should_listOwnAuthoredReviewWithSnapshotName_when_reviewedMasterIsDetached() throws Exception {
        BookingTestFixtures.VisitFixture visit = fixtures.createConfirmedVisit("mdc-my-reviews", 1);
        UUID bookingId = leadBookingId(visit.id());
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", bookingId);
        insertReview(bookingId, visit.clientId(), visit.masterId(), 5);

        detach(visit.masterId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/reviews/me", HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(visit.clientToken())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong()).isEqualTo(1L);
        JsonNode rows = page.path("data");
        assertThat(rows.size())
                .as("an INNER JOIN m.user here deletes the client's own writing from the list while "
                        + "the count query keeps counting it")
                .isEqualTo(page.path("totalElements").asInt());
        JsonNode row = rows.path(0);
        assertThat(row.path("bookingId").asText()).isEqualTo(bookingId.toString());
        assertThat(row.path("masterFirstName").asText())
                .as("the client's own receipt keeps naming the provider — unlike the anonymous "
                        + "salon listing of case 9, which masks it")
                .isEqualTo(DETACHED_FIRST);
        assertThat(row.path("masterLastName").asText()).isEqualTo(DETACHED_LAST);
    }

    // ── case 16 — QA gap: D4's OTHER arm. Case 2 only pins the DETACHED one ──────────────────────

    /**
     * D4 claims ATTACHED and DETACHED are "the only two representable states". Case 2 proves half
     * of that — a nulled {@code user_id} without a snapshot is rejected. The ATTACHED arm
     * ({@code user_id IS NOT NULL AND detached_at IS NULL}) was pinned by nothing, so a mutation
     * that dropped its {@code detached_at IS NULL} conjunct — or rewrote the whole CHECK as the
     * single-arm {@code user_id IS NOT NULL OR (detached_at IS NOT NULL AND detached_first_name IS
     * NOT NULL)} that reviewers reach for — left every existing case green.
     *
     * <p>The state this forbids is a LIVE master carrying a {@code detached_at}: {@code isDetached()}
     * keys on {@code user} and would still answer {@code false}, so the row reads as attached from
     * Java while the column says the account was erased. Phase 295's cascade is expected to select
     * its work by {@code detached_at IS NULL}; a row that lies about it is how a live master gets
     * skipped, or a deleted one gets processed twice.
     */
    @Test
    @DisplayName("case 16 — an ATTACHED master cannot carry a detached_at: the first arm of "
            + "chk_masters_detachment_coherent is pinned too, not only the second (D4)")
    void should_rejectDetachedAt_when_masterIsStillAttached() {
        UUID masterId = fixtures.createIndependentMaster(email("attached-with-detached-at"));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE masters SET detached_at = NOW() WHERE id = ?", masterId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_masters_detachment_coherent");

        // Even WITH a full name snapshot: it is detached_at, not the names, that discriminates —
        // so the second arm cannot be satisfied while user_id is still non-null.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE masters SET detached_at = NOW(), detached_first_name = ?, "
                        + "detached_last_name = ? WHERE id = ?",
                DETACHED_FIRST, DETACHED_LAST, masterId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_masters_detachment_coherent");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT detached_at FROM masters WHERE id = ?", java.sql.Timestamp.class, masterId))
                .as("neither rejected statement may have landed")
                .isNull();
    }

    // ── fixtures / helpers ─────────────────────────────────────────────────────────────────────

    private static String email(String discriminator) {
        return "mdc-" + discriminator + "-" + System.nanoTime() + "@beautica.test";
    }

    /**
     * Detaches a master exactly as phase 295 will: the name snapshot, {@code detached_at} and the
     * nulled {@code user_id} land in ONE statement, because chk_masters_detachment_coherent is
     * evaluated against the whole row. The {@code users} row is deliberately left in place — every
     * case that uses this helper is about the DETACHED masters row, not about the account delete
     * (case 5 covers that).
     */
    private void detach(UUID masterId) {
        jdbcTemplate.update(
                "UPDATE masters SET user_id = NULL, detached_first_name = ?, detached_last_name = ?, "
                        + "detached_at = NOW(), is_active = false WHERE id = ?",
                DETACHED_FIRST, DETACHED_LAST, masterId);
    }

    /**
     * Loads the entity with {@code user} initialised. {@code Master#user} is a LAZY
     * {@code @OneToOne}, so the read must happen inside a transaction — and it must happen in a
     * FRESH one, or a previous raw-SQL UPDATE would be shadowed by a stale first-level-cache entry.
     */
    private Master loadMaster(UUID masterId) {
        return tx.execute(status -> {
            Master master = masterRepository.findById(masterId).orElseThrow();
            // Touch the association inside the transaction so the returned entity is safe to read
            // after the template commits.
            master.displayFirstName();
            master.displayLastName();
            return master;
        });
    }

    /** What case 13 needs back from {@link #reparentOntoSalonMaster}. */
    private record BookingFixture(UUID masterId, String ownerToken) {}

    /**
     * Turns the visit fixture's independent-master booking into a plain SALON booking owned by a
     * fresh salon, and makes it completable.
     *
     * <p>Raw SQL on purpose, exactly like case 10's mixed-master visit: {@code BookingTestFixtures}
     * creates salon rows ({@code createSalon}) and independent-master visits
     * ({@code createConfirmedVisit}) but has no "confirmed salon booking" builder, and adding a
     * sixth booking-creation path to the shared fixtures for one case is how they drift. Three
     * things change and each is load-bearing for the endpoint under test:
     * <ol>
     *   <li>{@code master_id} → the salon's master, so {@code BookingCompletionAccess.salonId} is
     *       non-null and the OWNER arm of the authority predicate is the one being exercised;</li>
     *   <li>{@code appointment_id} → NULL, because {@code BookingService#completeBooking} refuses a
     *       multi-service visit item ({@code assertNotAppointmentChild}) and directs it to
     *       {@code /appointments/&#123;id&#125;} instead;</li>
     *   <li>{@code starts_at}/{@code ends_at} → the past, because
     *       {@code BookingTemporalGuard#assertElapsedForComplete} requires {@code now >= startsAt}.
     *       Without this the call 409s and the 403-vs-204 distinction the case exists for is lost.</li>
     * </ol>
     */
    private BookingFixture reparentOntoSalonMaster(UUID bookingId, String ownerEmail) throws Exception {
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        jdbcTemplate.update(
                "UPDATE bookings SET master_id = ?, appointment_id = NULL, "
                        + "starts_at = NOW() - INTERVAL '2 hours', ends_at = NOW() - INTERVAL '1 hour' "
                        + "WHERE id = ?",
                salon.masterId(), bookingId);
        return new BookingFixture(salon.masterId(), fixtures.tokenFor(salon.ownerEmail()));
    }

    private HttpStatus completeAs(UUID bookingId, String token) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId + "/complete", HttpMethod.PATCH,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    private UUID userIdOf(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private UUID leadBookingId(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at ASC LIMIT 1",
                UUID.class, appointmentId);
    }

    private void insertReview(UUID bookingId, UUID clientId, UUID masterId, int rating) {
        insertReview(bookingId, clientId, masterId, null, rating);
    }

    /**
     * Salon-scoped overload — {@code GET /salons/&#123;id&#125;/reviews} filters on
     * {@code reviews.salon_id}, not on the master's live affiliation, so case 9 needs the column
     * populated. The no-salon delegate above keeps every earlier case byte-identical.
     */
    private void insertReview(UUID bookingId, UUID clientId, UUID masterId, UUID salonId, int rating) {
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), bookingId, clientId, masterId, salonId, rating);
    }

    /** Every item of a visit, in the deterministic {@code starts_at} chain order. */
    private List<UUID> itemIdsOf(UUID appointmentId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at ASC",
                UUID.class, appointmentId);
    }

    private String statusOfBooking(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private int reviewCountOf(UUID masterId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT review_count FROM masters WHERE id = ?", Integer.class, masterId);
        return count == null ? -1 : count;
    }

    private BigDecimal avgRatingOf(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT avg_rating FROM masters WHERE id = ?", BigDecimal.class, masterId);
    }
}

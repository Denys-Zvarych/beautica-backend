package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.InvitePersistenceService;
import com.beautica.auth.Role;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Phase 296 — the acceptance test for the whole 2026-09-04 salon-deletion reversal. See
 * {@code docs/backend-phases/phase-296-reinvite-deleted-staff-acceptance.md}.
 *
 * <p>The user's own criterion, in their words:
 * <em>"deleted salon staff should be easy invited again because they shouldn't exist in our DB"</em>.
 * Phases 294 and 295 make the {@code users} row disappear; this class walks the whole loop and
 * proves the invite behaves as a first-ever invite: owner creates salon A, invites a master, the
 * master accepts, works, is reviewed, the salon is deleted, the owner opens salon B and invites the
 * SAME address again — {@code 201}, not {@code 409 EMAIL_ALREADY_REGISTERED}.
 *
 * <p><b>D1 — this is one integration test, not a feature.</b> No production file is touched. If a
 * line of {@code InviteService} or {@code SalonService} had to change to make this green, goal #2
 * was not achieved by deletion and that is a finding, not a fix.
 *
 * <p><b>D2 — the master has REAL history.</b> Every fixture here gives the master a past
 * {@code COMPLETED} booking and a review BEFORE the delete, so the phase 295 D1 split is what runs:
 * {@code users} deleted, {@code masters} DETACHED as a name-only stub. A freshly-invited master who
 * never worked deletes outright and proves nothing interesting — that is the easy path, and it is
 * deliberately not the one exercised here.
 *
 * <p><b>D3/D4 — the re-invite creates a NEW identity.</b> {@code MasterService
 * #createMasterFromInvite} builds a fresh {@code masters} row against salon B. The detached stub is
 * never reattached, never searched for, never deduplicated against, and its {@code avg_rating} /
 * {@code review_count} stay on the stub. The re-invited person starts at zero. Cases 5 and 4 pin
 * both halves; they are the contract, not a bug.
 *
 * <p><b>What this class deliberately does NOT re-prove.</b> {@link SalonStaffHardDeleteIT} (17
 * cases) already pins that no {@code users} row survives, that pending invites are swept, that a
 * master with history survives as an email-less stub, that the deleted account's outstanding access
 * token stops authenticating, and that a second delete is a clean no-op. Those are preconditions
 * here, referenced rather than re-seeded.
 *
 * <p><b>THE TRAP, asserted rather than assumed.</b> Re-invite was historically blocked by
 * {@code userRepository.existsByEmail} ({@code InviteService:159}, thrown as a 409 at
 * {@code :206-208} since phase 287) — NOT by the {@code ux_invite_tokens_active} unique index. Case
 * 1 therefore asserts that exact predicate directly, TRUE before the delete and FALSE after, so a
 * 201 that happened to arrive for some unrelated reason cannot pass this class off as green while a
 * future regression in the delete goes unnoticed. Case 1 also carries a live NEGATIVE CONTROL: a
 * still-registered address invited to the same salon B in the same test must still 409, proving the
 * gate is armed in exactly this context rather than globally disabled.
 *
 * <h3>Rate-limit budget — why setup invites do not go over HTTP</h3>
 * {@code POST /api/v1/salons/&#123;salonId&#125;/invite} is throttled per IP at
 * {@code AuthRateLimitFilter.SALON_INVITE_CAPACITY} = 15/60 s, and that constant is
 * <b>not</b> {@code @Value}-configurable, so {@code application-test.yml} cannot raise it the way
 * it raises every other capacity. Every test in this class runs from 127.0.0.1 and shares one
 * bucket. The RE-INVITE — the call actually under test — is always an HTTP request; the salon-A
 * SETUP invite calls {@code salonService.inviteMaster}, which is the very method the controller
 * delegates to, one HTTP hop away. That keeps this class at 8 throttled requests instead of 15 and
 * off the edge of the bucket.
 *
 * <h3>Mutation check (both mandatory — phase doc § Mutation check)</h3>
 * <ul>
 *   <li><b>Phase 291's tombstone restored in place of the delete</b>, in
 *       {@code SalonService#deleteSalonStaff}. Run in BOTH shapes a tombstone can take, because
 *       they are not equivalent and the phase doc names only one outcome.
 *       <ul>
 *         <li>address KEPT (the {@code deleteAllByIdInBatch} call removed, row left standing) —
 *             ✅ EXECUTED, <b>7/7 RED</b>, and case 1 fails on the {@code existsByEmail} trap
 *             assertion itself, before it ever reaches a status code;</li>
 *         <li>address SCRUBBED (row updated to {@code deleted+&lt;id&gt;@…}, {@code is_active =
 *             false} — phase 291 D9's actual PII redaction) — ✅ EXECUTED, <b>exactly case 3
 *             RED</b>, on its "the OLD users.id is ABSENT" assertion. Every other case stays
 *             GREEN, including case 1: a scrubbed row frees the address just as effectively as a
 *             deleted one. That is why case 3 does not stop at the {@code count(*) WHERE email = ?}
 *             the phase doc names — see case 3's javadoc.</li>
 *       </ul></li>
 *   <li><b>Phase 295 step 3 skipped</b> ({@code inviteTokenRepository
 *       .deleteBySalonIdAndStaffUserIds(...)} commented out). ✅ EXECUTED, <b>exactly case 6
 *       RED</b>, on its sweep assertion — but NOT "on the {@code V101} unique index" as the phase
 *       doc predicts, and the salon-B re-invite still returned 201 under the mutation. The doc is
 *       right about the case and wrong about the mechanism; see case 6's javadoc for the measured
 *       reason. Recorded, not papered over.</li>
 *   <li><b>Narrow the sweep to {@code AND t.is_used = false}</b> — the plausible future reading of
 *       the "a stale PENDING row would collide" rationale at {@code SalonService:979-980}.
 *       ✅ EXECUTED, <b>exactly case 8 RED</b>; cases 1-7 and 9 all stayed GREEN, which is why
 *       case 8 exists.</li>
 *   <li><b>Drop {@code AND t.salon_id = :salonId} from the sweep</b> — the cascade reaching into a
 *       third party's invite history. ✅ EXECUTED, <b>exactly case 9 RED</b>; case 8 stays green
 *       because the mutation deletes strictly more than it should.</li>
 * </ul>
 *
 * <h3>Coverage note — {@code invite_tokens} is pinned HERE, nowhere else</h3>
 * {@link SalonStaffHardDeleteIT} (945 lines) contains no assertion on {@code invite_tokens} at all.
 * Phase 295's forward note lists the sweep as "exercised by every deleting case" — true of
 * execution, not of coverage. Cases 6, 8 and 9 are the whole of this repo's coverage of
 * {@code InviteTokenRepository#deleteBySalonIdAndStaffUserIds}, one per axis of its {@code WHERE}
 * clause: which rows (6, pending), which statuses (8, status-blind), which salon (9, scoped).
 */
@DisplayName("Phase 296 — re-inviting deleted salon staff, end to end")
class SalonStaffReinviteAfterDeletionIT extends AbstractIntegrationTest {

    /**
     * The password the master sets when redeeming the SALON A invite. Aliased to the package's
     * shared literal rather than duplicating its value: case 2 asserts that this exact string stops
     * authenticating after the delete, and a silent divergence between the two literals would
     * weaken that 401 into a tautology. Same aliasing as {@code SalonStaffEndpointIT:58}.
     */
    private static final String FIRST_PASSWORD = SalonItFixtures.TEST_PASSWORD;
    /** A genuinely different password, set when redeeming the SALON B invite (case 2). */
    private static final String SECOND_PASSWORD = "N0vaP@rol9x!";
    private static final String PHONE = "+380501234567";

    /** The name snapshotted onto the detached stub, and therefore onto the client's old receipt. */
    private static final String OLD_FIRST_NAME = "Олена";
    private static final String OLD_LAST_NAME = "Коваленко";
    /** A DIFFERENT name on the second account, so case 4 cannot pass by coincidence. */
    private static final String NEW_FIRST_NAME = "Марія";
    private static final String NEW_LAST_NAME = "Шевченко";

    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);

    @Autowired
    private SalonService salonService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * The production bean whose {@code existsByEmail} IS the gate under test. Asserted directly so
     * the acceptance criterion reads against the same predicate {@code InviteService:159} calls,
     * not against a hand-rolled {@code SELECT count(*)} that could drift from it (case 3 does carry
     * the raw SQL count as well — the phase doc asks for both).
     */
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * The only way to observe the RAW invite token: {@code invite_tokens.token} stores an
     * irreversible hash, and the plaintext exists exactly once — as the {@code inviteLink} argument
     * handed to {@link InvitePersistenceService#persistInviteAndEnqueue}. Spying the real bean (not
     * mocking it) leaves every write and the outbox enqueue fully intact, so the loop stays real;
     * the spy only reads an argument in flight.
     */
    @SpyBean
    private InvitePersistenceService invitePersistenceService;

    private SalonItFixtures fx;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        fx = new SalonItFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder,
                this::testCityId);
        tx = new TransactionTemplate(transactionManager);
    }

    // ── case 1 — THE LOOP, and the acceptance criterion for the whole reversal ────────────────

    /**
     * Acceptance criterion 1 of the phase doc: <em>"This single assertion is the acceptance
     * criterion for the entire 2026-09-04 reversal; everything in 294 and 295 is machinery in
     * service of it."</em>
     *
     * <p>The two {@code existsByEmail} assertions are the point of the case, not decoration. The
     * 409 that used to block this loop came from that predicate, so proving it flips from TRUE to
     * FALSE across the delete is what makes the 201 below attributable to the hard delete rather
     * than to any other change in the invite path.
     */
    @Test
    @DisplayName("case 1 — owner deletes salon A, opens salon B and re-invites the SAME address: "
            + "201, not 409 EMAIL_ALREADY_REGISTERED")
    void should_return201_when_reInvitingTheAddressOfHardDeletedSalonStaff() throws Exception {
        Loop loop = buildSalonAWithWorkedMaster();

        assertThat(userRepository.existsByEmail(loop.staffEmail()))
                .as("precondition — InviteService:159's gate (userRepository.existsByEmail) is TRUE "
                        + "while the account lives, so the 201 below cannot pass vacuously on a "
                        + "check that was never armed")
                .isTrue();

        deleteSalon(loop.ownerToken(), loop.salonAId());

        assertThat(userRepository.existsByEmail(loop.staffEmail()))
                .as("THE trap, asserted not assumed: re-invite was blocked by existsByEmail — not "
                        + "by ux_invite_tokens_active — and the phase 295 hard delete is what "
                        + "flips it. A future regression that stops deleting the row fails HERE, "
                        + "not silently downstream")
                .isFalse();

        UUID salonB = fx.insertSalon(loop.ownerId(), "Salon B " + System.nanoTime());
        ResponseEntity<String> reinvite =
                postInvite(loop.ownerToken(), salonB, loop.staffEmail(), Role.SALON_MASTER);

        assertThat(reinvite.getStatusCode())
                .as("acceptance criterion 1 — the whole reversal reduces to this status code")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(dataOf(reinvite).path("invitedEmail").asText())
                .as("a real invite response for the freed address, not an empty envelope")
                .isEqualTo(loop.staffEmail());
        assertThat(countLiveInvites(loop.staffEmail(), salonB))
                .as("a redeemable token really was minted — a silently-swallowed idempotent 201 "
                        + "would also be a 201, and would leave no row here")
                .isEqualTo(1);

        // NEGATIVE CONTROL, same test, same salon, same bucket: an address that IS still registered
        // must still be refused. Without it, a 201 above would be equally consistent with the 409
        // gate having been removed outright rather than with the row having been deleted.
        String liveEmail = fx.emailOf(fx.insertUser("reinvite-live-" + System.nanoTime()
                + "@beautica.test", "CLIENT"));
        assertThat(postInvite(loop.ownerToken(), salonB, liveEmail, Role.SALON_MASTER).getStatusCode())
                .as("the EMAIL_ALREADY_REGISTERED gate is armed in exactly this context — the 201 "
                        + "above is a deleted row, not a disabled check")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ── case 2 — the invitee redeems and logs in on a NEW password ───────────────────────────

    @Test
    @DisplayName("case 2 — the re-invited master accepts and logs in with a NEW password; the "
            + "password of the deleted account no longer authenticates")
    void should_loginWithNewPassword_when_reInvitedMasterAcceptsSecondInvite() throws Exception {
        Loop loop = buildSalonAWithWorkedMaster();
        deleteSalon(loop.ownerToken(), loop.salonAId());
        UUID salonB = fx.insertSalon(loop.ownerId(), "Salon B " + System.nanoTime());

        String rawToken = postInviteAndCaptureToken(
                loop.ownerToken(), salonB, loop.staffEmail(), Role.SALON_MASTER);
        ResponseEntity<String> accepted =
                acceptInvite(rawToken, SECOND_PASSWORD, NEW_FIRST_NAME, NEW_LAST_NAME);

        assertThat(accepted.getStatusCode())
                .as("the second invite is redeemable end to end — a token, not just a 201")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(dataOf(accepted).path("accessToken").asText())
                .as("acceptance mints a working session on the spot")
                .isNotBlank();
        assertThat(login(loop.staffEmail(), SECOND_PASSWORD).getStatusCode())
                .as("the NEW password is the account's password")
                .isEqualTo(HttpStatus.OK);
        assertThat(login(loop.staffEmail(), FIRST_PASSWORD).getStatusCode())
                .as("there is no old password to fall back to — the row that held its hash is "
                        + "gone, so this is a plain unknown-credential 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── case 3 — exactly one row holds the address, and it is the NEW one ────────────────────

    /**
     * <b>MEASURED — this is the case that discriminates deletion from renaming.</b> The phase doc's
     * first mutation check asks for phase 291's tombstone to be restored in place of the delete and
     * predicts "two rows: the tombstone and the new account". Executed, and the prediction needs
     * refining, so it is recorded here rather than restated:
     * <ul>
     *   <li>a tombstone that <b>keeps</b> the address ({@code UPDATE users SET is_active = false})
     *       never reaches this case at all — {@code existsByEmail} stays TRUE, the re-invite 409s,
     *       and cases 1, 2, 3, 4, 5 and 7 all go red at the invite;</li>
     *   <li>a tombstone that <b>scrubs</b> the address (phase 291 D9's actual PII redaction) lets
     *       the invite through, and the plain {@code count(*) WHERE email = ?} the doc names would
     *       still read 1 — the surviving row no longer carries that address. The bare count does
     *       NOT discriminate that variant.</li>
     * </ul>
     * Hence the third assertion: the OLD {@code users.id} must be absent. It is the one predicate
     * red under BOTH tombstone shapes, and it is why this case does not stop at a count. It
     * overlaps {@link SalonStaffHardDeleteIT} case 1 by design — 295 pins it as an end state, 296
     * needs it as the discriminator that keeps this class honest.
     */
    @Test
    @DisplayName("case 3 — exactly ONE users row holds the address after the re-invite, and it is "
            + "a different id than the deleted account's")
    void should_holdExactlyOneUsersRow_when_addressIsReInvitedAndAccepted() throws Exception {
        Loop loop = buildSalonAWithWorkedMaster();
        deleteSalon(loop.ownerToken(), loop.salonAId());
        UUID salonB = fx.insertSalon(loop.ownerId(), "Salon B " + System.nanoTime());
        String rawToken = postInviteAndCaptureToken(
                loop.ownerToken(), salonB, loop.staffEmail(), Role.SALON_MASTER);

        acceptInvite(rawToken, SECOND_PASSWORD, NEW_FIRST_NAME, NEW_LAST_NAME);

        assertThat(countUsersWithEmail(loop.staffEmail()))
                .as("one address, one account — never an account plus a tombstone")
                .isEqualTo(1);
        assertThat(userIdOf(loop.staffEmail()))
                .as("and it is a NEW identity (D3), not the old row revived")
                .isNotEqualTo(loop.oldUserId());
        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ?", loop.oldUserId()))
                .as("the deleted account's row is ABSENT, not renamed — the assertion both "
                        + "tombstone shapes fail (see this case's javadoc)")
                .isZero();
    }

    // ── case 4 — the client's old receipt is untouched by the new identity ───────────────────

    /**
     * Phase 294 D3, re-checked at the far end of the loop. {@link SalonStaffHardDeleteIT} case 3
     * proves the name survives the DELETE; what is new here is that it survives the RE-INVITE — the
     * second account deliberately carries a different name, so a naive "reconnect the person"
     * implementation (the thing D3 forbids) would rewrite this receipt to
     * {@code Марія Шевченко} and go red.
     */
    @Test
    @DisplayName("case 4 — the client's pre-delete COMPLETED booking still renders the ORIGINAL "
            + "provider name after the same person is re-invited under a new account")
    void should_stillRenderOriginalProviderName_when_staffWasReInvitedUnderANewAccount() throws Exception {
        Loop loop = buildSalonAWithWorkedMaster();
        deleteSalon(loop.ownerToken(), loop.salonAId());
        UUID salonB = fx.insertSalon(loop.ownerId(), "Salon B " + System.nanoTime());
        String rawToken = postInviteAndCaptureToken(
                loop.ownerToken(), salonB, loop.staffEmail(), Role.SALON_MASTER);
        acceptInvite(rawToken, SECOND_PASSWORD, NEW_FIRST_NAME, NEW_LAST_NAME);

        String clientToken = fx.loginAndGetToken(loop.clientEmail());
        ResponseEntity<String> booking = restTemplate.exchange(
                "/api/v1/bookings/" + loop.bookingId(), HttpMethod.GET,
                new HttpEntity<>(fx.bearerHeaders(clientToken)), String.class);

        assertThat(booking.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(booking.getBody()).path("data");
        assertThat(data.path("masterFirstName").asText())
                .as("the receipt keeps the snapshot taken at detach time — the re-invited person's "
                        + "new name must never leak back onto somebody else's old visit (D3)")
                .isEqualTo(OLD_FIRST_NAME);
        assertThat(data.path("masterLastName").asText()).isEqualTo(OLD_LAST_NAME);
    }

    // ── case 5 — D3/D4: a new masters row, starting at zero ──────────────────────────────────

    @Test
    @DisplayName("case 5 — the re-invited master gets a NEW masters row at zero ratings; the "
            + "detached stub keeps the old aggregate and is never reattached (D3, D4)")
    void should_createFreshMasterRowAtZeroRatings_when_deletedStaffIsReInvited() throws Exception {
        Loop loop = buildSalonAWithWorkedMaster();
        // Fixture check — the stub's aggregate genuinely MOVED off zero before the delete, so the
        // zeros asserted below cannot pass against an aggregate that was never populated.
        assertThat(reviewCountOf(loop.oldMasterId())).isEqualTo(1);
        assertThat(avgRatingOf(loop.oldMasterId())).isEqualByComparingTo(new BigDecimal("5.00"));

        deleteSalon(loop.ownerToken(), loop.salonAId());
        UUID salonB = fx.insertSalon(loop.ownerId(), "Salon B " + System.nanoTime());
        String rawToken = postInviteAndCaptureToken(
                loop.ownerToken(), salonB, loop.staffEmail(), Role.SALON_MASTER);
        acceptInvite(rawToken, SECOND_PASSWORD, NEW_FIRST_NAME, NEW_LAST_NAME);

        UUID newUserId = userIdOf(loop.staffEmail());
        UUID newMasterId = masterIdOfUser(newUserId);

        assertThat(newMasterId)
                .as("D3 — createMasterFromInvite builds a fresh row against salon B; the stub is "
                        + "not searched for and not deduplicated against")
                .isNotEqualTo(loop.oldMasterId());
        assertThat(masterSalonId(newMasterId)).isEqualTo(salonB);
        assertThat(reviewCountOf(newMasterId))
                .as("D4 — the old ratings do not follow the person")
                .isZero();
        assertThat(avgRatingOf(newMasterId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(reviewCountOf(loop.oldMasterId()))
                .as("and they stay where they were earned — on the stub, on somebody else's "
                        + "old receipt")
                .isEqualTo(1);

        // D4 at ROW level, not just on the denormalized aggregate. masters.review_count /
        // avg_rating are derived columns: a regression that re-points reviews.master_id at the new
        // master WITHOUT calling recalculateMasterRating leaves both aggregates exactly as asserted
        // above and slips through every case in this class. The reviews table is the truth the
        // aggregate is computed FROM, so it is what D4 has to be pinned on.
        assertThat(count("SELECT COUNT(*) FROM reviews WHERE master_id = ?", newMasterId))
                .as("D4, row level — not one reviews row was re-pointed at the new master; the "
                        + "aggregate zeros above would survive a re-point that skipped the "
                        + "recalculation, this assertion would not")
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM reviews WHERE master_id = ?", loop.oldMasterId()))
                .as("and the review row itself still hangs off the stub — the aggregate on the "
                        + "stub is backed by real data, not a number left behind by an empty table")
                .isEqualTo(1);

        // D3 at row level, symmetrically: `id` and `salon_id` alone say nothing about what ELSE
        // the fresh row might have inherited. createMasterFromInvite builds a bare Master
        // (user, salon, type, zeroed aggregates, is_active) — nothing on the stub's side of the
        // detach is copied, and these are the surfaces where a "reconnect the person"
        // implementation would show up first.
        assertThat(count("SELECT COUNT(*) FROM master_services WHERE master_id = ?", newMasterId))
                .as("D3 — the fresh row carries NO offering from the detached stub; the "
                        + "re-invited master starts with an empty service list, not salon A's")
                .isZero();
        assertThat(count(
                "SELECT COUNT(*) FROM media_files WHERE entity_type = 'MASTER' AND entity_id = ?",
                newMasterId))
                .as("D3 — no portfolio or avatar media is re-pointed at the new master row")
                .isZero();
        assertThat(count(
                "SELECT COUNT(*) FROM media_files WHERE entity_type = 'MASTER' AND entity_id = ?",
                loop.oldMasterId()))
                .as("...and the media the fixture seeded is genuinely still THERE, on the stub. "
                        + "Without this companion the assertion above reads 0 == 0 and cannot go "
                        + "red — see insertMasterPortfolioMedia's javadoc for why the row has to "
                        + "be seeded rather than uploaded")
                .isEqualTo(1);
        assertThat(count(
                "SELECT COUNT(*) FROM masters WHERE id = ? AND min_effective_price IS NULL "
                        + "AND detached_first_name IS NULL AND detached_last_name IS NULL "
                        + "AND detached_at IS NULL",
                newMasterId))
                .as("D3 — the fresh row is a LIVE master, not a copy of the stub: no carried-over "
                        + "min_effective_price and none of the V157 detachment snapshot")
                .isEqualTo(1);
        assertThat(masterUserId(loop.oldMasterId()))
                .as("the stub is NOT re-pointed at the new account — that would re-introduce "
                        + "exactly the linkage R1 removed")
                .isNull();
    }

    // ── case 6 — a stale PENDING invite at salon A does not follow the address ───────────────

    /**
     * <b>MEASURED — the phase doc's second mutation check is right about the case and wrong about
     * the mechanism, and that is recorded here rather than restated.</b>
     *
     * <p>The doc predicts: skip phase 295 step 3 → "case 6 must go red on the {@code V101} unique
     * index". It cannot, and no assertion could make it. {@code ux_invite_tokens_active} is
     * {@code (lower(email), salon_id) WHERE is_used = false AND revoked_at IS NULL} (V101, re-scoped
     * by V153) — <b>salon-scoped</b>. Salon A's stale row and salon B's new row differ in
     * {@code salon_id}, so they can never collide; {@code InviteService#sendInvite}'s idempotency
     * pre-check is salon-scoped for the same reason (see V101's own header). Deleting a salon is a
     * SOFT delete, so the {@code salons} row survives and {@code invite_tokens.salon_id}'s
     * {@code ON DELETE SET NULL} (V5) never fires either — there is no path to a NULL
     * {@code salon_id} that could collapse the two keys.
     *
     * <p>What the mutation DOES break is the first assertion below: with
     * {@code deleteBySalonIdAndStaffUserIds} commented out, the stale PENDING row survives the
     * cascade and salon A's history is left holding a live invite for an address whose account no
     * longer exists. ✅ EXECUTED, RED on that assertion. The case is therefore kept exactly as the
     * doc scopes it, with the mechanism corrected.
     */
    @Test
    @DisplayName("case 6 — a stale PENDING invite for the address at salon A is swept by the "
            + "cascade and does not interfere with the salon B invite")
    void should_sweepStalePendingInvite_when_salonIsDeleted() throws Exception {
        Loop loop = buildSalonAWithWorkedMaster();
        UUID staleInviteId = insertPendingInvite(loop.staffEmail(), loop.salonAId());
        assertThat(count("SELECT COUNT(*) FROM invite_tokens WHERE id = ?", staleInviteId))
                .as("fixture check — the stale row exists BEFORE the cascade, so the zero below "
                        + "proves a sweep rather than an insert that never happened")
                .isEqualTo(1);

        deleteSalon(loop.ownerToken(), loop.salonAId());

        assertThat(count("SELECT COUNT(*) FROM invite_tokens WHERE id = ?", staleInviteId))
                .as("phase 295 step 3 — no live invite for a hard-deleted address may outlive the "
                        + "salon that issued it (this is the assertion the mutation reddens)")
                .isZero();

        UUID salonB = fx.insertSalon(loop.ownerId(), "Salon B " + System.nanoTime());
        ResponseEntity<String> reinvite =
                postInvite(loop.ownerToken(), salonB, loop.staffEmail(), Role.SALON_MASTER);

        assertThat(reinvite.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(countLiveInvites(loop.staffEmail(), salonB))
                .as("a genuine token for salon B, not an idempotent short-circuit against a "
                        + "leftover salon A row")
                .isEqualTo(1);
    }

    // ── case 7 — the deleted account's role constrains nothing ───────────────────────────────

    @Test
    @DisplayName("case 7 — the address may be re-invited as SALON_ADMIN even though the deleted "
            + "account was a SALON_MASTER: the old role constrains nothing")
    void should_reInviteUnderADifferentRole_when_deletedAccountWasASalonMaster() throws Exception {
        Loop loop = buildSalonAWithWorkedMaster();
        assertThat(loop.oldRole())
                .as("fixture check — the deleted account really was a SALON_MASTER, so the "
                        + "SALON_ADMIN acceptance below is a genuine role change")
                .isEqualTo("SALON_MASTER");

        deleteSalon(loop.ownerToken(), loop.salonAId());
        UUID salonB = fx.insertSalon(loop.ownerId(), "Salon B " + System.nanoTime());
        String rawToken = postInviteAndCaptureToken(
                loop.ownerToken(), salonB, loop.staffEmail(), Role.SALON_ADMIN);

        assertThat(acceptInvite(rawToken, SECOND_PASSWORD, NEW_FIRST_NAME, NEW_LAST_NAME)
                .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        UUID newUserId = userIdOf(loop.staffEmail());
        assertThat(userRole(newUserId)).isEqualTo("SALON_ADMIN");
        assertThat(userSalonId(newUserId)).isEqualTo(salonB);
        assertThat(count("SELECT COUNT(*) FROM masters WHERE user_id = ?", newUserId))
                .as("a SALON_ADMIN has no masters row at all — nothing was carried over from the "
                        + "master identity the address used to hold")
                .isZero();
    }

    // ── case 8 — the REDEEMED salon-A invite carries the address too, and is swept ───────────

    /**
     * <b>The half of "they shouldn't exist in our DB" that lives outside {@code users}.</b>
     *
     * <p>{@code invite_tokens.email} stores the address verbatim, and the salon-A invite the master
     * actually redeemed is still sitting there when the cascade runs, {@code is_used = true}. Delete
     * the account and leave that row and the address has not left the database at all — it has just
     * moved table. {@code InviteTokenRepository#deleteBySalonIdAndStaffUserIds} is deliberately
     * <b>status-blind</b> for exactly this reason (its javadoc, {@code :145-148}: "PENDING, ACCEPTED,
     * EXPIRED and SUPERSEDED rows for this salon+address all go alike").
     *
     * <p><b>Why case 6 does not already cover this.</b> Case 6 seeds a PENDING row and asserts it is
     * swept. The rationale written at the call site ({@code SalonService:979-980}) names only the
     * collision motive — "a stale PENDING row would collide with the phase 296 re-invite" — so the
     * obvious narrowing for a future reader is {@code AND t.is_used = false}. Under that mutation
     * case 6 stays GREEN, every other case in this class stays GREEN, and the redeemed row keeps the
     * address forever. Measured: ✅ EXECUTED, and <b>only this case</b> goes RED.
     *
     * <p>Nothing in {@link SalonStaffHardDeleteIT} asserts on {@code invite_tokens} at all (grep: the
     * class's only {@code is_used} reference is {@code password_reset_tickets}), so phase 295's
     * forward note calling the sweep "exercised by every deleting case" is about execution, not
     * coverage. This case and case 9 are where the statement's {@code WHERE} clause is pinned.
     *
     * <p>No HTTP invite here — the class's rate-limit budget is unchanged at 8 throttled requests.
     */
    @Test
    @DisplayName("case 8 — the REDEEMED salon-A invite row is swept as well as the pending one: no "
            + "invite_tokens row survives holding the hard-deleted address")
    void should_sweepTheRedeemedInviteRow_when_salonIsDeleted() throws Exception {
        Loop loop = buildSalonAWithWorkedMaster();

        assertThat(count(
                "SELECT COUNT(*) FROM invite_tokens WHERE lower(email) = lower(?) AND is_used = true",
                loop.staffEmail()))
                .as("fixture check — the invite the master actually redeemed is present and marked "
                        + "used BEFORE the cascade, so the zero below is a sweep and not an absence")
                .isEqualTo(1);

        deleteSalon(loop.ownerToken(), loop.salonAId());

        assertThat(count("SELECT COUNT(*) FROM invite_tokens WHERE lower(email) = lower(?)",
                loop.staffEmail()))
                .as("the address is gone from invite_tokens too — the sweep is status-blind, so a "
                        + "narrowing to `is_used = false` (which case 6 would not notice) leaves "
                        + "the hard-deleted address standing in this salon's invite history")
                .isZero();
    }

    // ── case 9 — the sweep is salon-scoped; another salon's invite is not collateral ─────────

    /**
     * The mirror of case 8, on the other axis of the same {@code WHERE} clause. The statement is
     * scoped {@code AND t.salon_id = :salonId} precisely so that a salon deletion cannot reach into
     * a <b>different</b> salon's invite history ({@code InviteTokenRepository:141-143}: "that row
     * belongs to that other salon's own history and must survive this cascade untouched").
     *
     * <p>This is the acceptance claim read the strong way. "Easy invited again" is worth least when
     * the owner who deleted the salon has to be the one to re-invite; the case that matters is a
     * salon that had <em>already</em> invited this person independently. Deleting salon A must not
     * silently void salon C's outstanding offer.
     *
     * <p>The row is seeded rather than dispatched because phase 287's
     * {@code EmailAlreadyRegisteredException} makes salon C's invite impossible through the API
     * while the master is employed at salon A — the realistic sequence (salon C invites first, the
     * master accepts salon A instead) leaves exactly this row shape, and a raw insert is the honest
     * way to represent it. Same reasoning as {@link #insertPendingInvite}.
     *
     * <p>Measured: dropping {@code AND t.salon_id = :salonId} from the statement takes <b>only this
     * case</b> RED. Case 8 stays green under that mutation — it asserts deletion, and the mutation
     * deletes strictly more.
     */
    @Test
    @DisplayName("case 9 — deleting salon A leaves a DIFFERENT salon's pending invite for the same "
            + "address live and untouched: the sweep is salon-scoped")
    void should_leaveAnotherSalonsPendingInviteLive_when_salonIsDeleted() throws Exception {
        Loop loop = buildSalonAWithWorkedMaster();

        UUID otherOwnerId = fx.insertUser(
                "reinvite-owner-c-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonC = fx.insertSalon(otherOwnerId, "Salon C " + System.nanoTime());
        UUID siblingInviteId = insertPendingInvite(loop.staffEmail(), salonC);

        deleteSalon(loop.ownerToken(), loop.salonAId());

        assertThat(count("SELECT COUNT(*) FROM invite_tokens WHERE id = ? AND salon_id = ? "
                        + "AND is_used = false AND revoked_at IS NULL", siblingInviteId, salonC))
                .as("salon C's outstanding offer survives salon A's deletion, still LIVE — the "
                        + "cascade is scoped to the deleting salon, and an unscoped sweep would "
                        + "destroy a third party's invite history as a side effect")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT email FROM invite_tokens WHERE id = ?", String.class, siblingInviteId))
                .as("and it still carries the address, so it is genuinely redeemable rather than "
                        + "surviving as a blanked shell")
                .isEqualTo(loop.staffEmail());
        assertThat(userRepository.existsByEmail(loop.staffEmail()))
                .as("the address is free at the same time — salon C's invitee can register on it "
                        + "with no 409 from InviteService:159, which is what makes the surviving "
                        + "row worth surviving")
                .isFalse();
    }

    // ── the loop fixture ────────────────────────────────────────────────────────────────────

    /**
     * The salon-A half of the loop, shared by every case: a real invite, a real acceptance, a
     * completed booking and a review — the D2 "master with real history" precondition, which is
     * what forces the phase 295 detach branch instead of the trivial delete-outright branch.
     */
    private record Loop(UUID ownerId, String ownerToken, UUID salonAId, String staffEmail,
                        UUID oldUserId, UUID oldMasterId, String oldRole,
                        String clientEmail, UUID bookingId) {}

    private Loop buildSalonAWithWorkedMaster() throws Exception {
        String ownerEmail = "reinvite-owner-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = fx.insertUser(ownerEmail, "SALON_OWNER");
        UUID salonAId = fx.insertSalon(ownerId, "Salon A " + System.nanoTime());
        String ownerToken = fx.loginAndGetToken(ownerEmail);

        String staffEmail = "reinvite-staff-" + System.nanoTime() + "@beautica.test";
        // Setup invite via the service the controller delegates to — see the class javadoc's
        // rate-limit budget note for why this one hop is not made over HTTP.
        salonService.inviteMaster(ownerId, salonAId, staffEmail, Role.SALON_MASTER);
        String rawToken = lastCapturedRawToken();
        assertThat(acceptInvite(rawToken, FIRST_PASSWORD, OLD_FIRST_NAME, OLD_LAST_NAME)
                .getStatusCode())
                .as("the first invite must genuinely redeem, or the whole loop is untested")
                .isEqualTo(HttpStatus.CREATED);

        UUID oldUserId = userIdOf(staffEmail);
        UUID oldMasterId = masterIdOfUser(oldUserId);

        UUID serviceDefId = insertServiceDefinition(salonAId);
        UUID masterServiceId = insertMasterService(oldMasterId, serviceDefId);

        String clientEmail = "reinvite-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fx.insertUser(clientEmail, "CLIENT");
        UUID bookingId = insertCompletedBooking(clientId, oldMasterId, masterServiceId, salonAId);
        insertReview(bookingId, clientId, oldMasterId, salonAId);
        tx.executeWithoutResult(s -> reviewRepository.recalculateMasterRating(oldMasterId));
        insertMasterPortfolioMedia(ownerId, oldMasterId);

        return new Loop(ownerId, ownerToken, salonAId, staffEmail, oldUserId, oldMasterId,
                userRole(oldUserId), clientEmail, bookingId);
    }

    // ── HTTP ────────────────────────────────────────────────────────────────────────────────

    private ResponseEntity<String> postInvite(String ownerToken, UUID salonId, String email, Role role) {
        return restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/invite", HttpMethod.POST,
                new HttpEntity<>(new InviteRequest(email, salonId, role), fx.bearerHeaders(ownerToken)),
                String.class);
    }

    private String postInviteAndCaptureToken(String ownerToken, UUID salonId, String email, Role role) {
        assertThat(postInvite(ownerToken, salonId, email, role).getStatusCode())
                .as("the re-invite under test must be a real 201 before its token is redeemed")
                .isEqualTo(HttpStatus.CREATED);
        return lastCapturedRawToken();
    }

    private ResponseEntity<String> acceptInvite(String rawToken, String password,
                                                String firstName, String lastName) {
        return restTemplate.postForEntity(
                "/api/v1/auth/invite/accept",
                new InviteAcceptRequest(rawToken, password, firstName, lastName, PHONE),
                String.class);
    }

    private ResponseEntity<String> login(String email, String password) {
        return restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, password), String.class);
    }

    private void deleteSalon(String ownerToken, UUID salonId) {
        assertThat(restTemplate.exchange(
                "/api/v1/salons/" + salonId, HttpMethod.DELETE,
                new HttpEntity<>(fx.bearerHeaders(ownerToken)), Void.class).getStatusCode())
                .as("the delete must actually run — a 409 from the phase 289 staff-as-client "
                        + "audit here would silently turn every case below into a no-op")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * The last raw invite token minted in this test. {@code inviteLink} is the only place the
     * plaintext exists outside the invitee's mailbox; {@code invite_tokens.token} holds an
     * irreversible hash.
     */
    private String lastCapturedRawToken() {
        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(invitePersistenceService, atLeastOnce()).persistInviteAndEnqueue(
                anyString(), any(UUID.class), any(Role.class), any(Instant.class),
                anyString(), linkCaptor.capture(), anyString());
        List<String> links = linkCaptor.getAllValues();
        String link = links.get(links.size() - 1);
        String encoded = link.substring(link.indexOf("token=") + "token=".length());
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private JsonNode dataOf(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).path("data");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private UUID insertServiceDefinition(UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                id, salonId, resolveUnusedServiceTypeId("SALON", salonId));
        return id;
    }

    private UUID insertMasterService(UUID masterId, UUID serviceDefId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, "
                        + "updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                id, masterId, serviceDefId);
        return id;
    }

    private UUID insertCompletedBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                id, clientId, masterId, masterServiceId, salonId, PAST, PAST.plusMinutes(60));
        return id;
    }

    private void insertReview(UUID bookingId, UUID clientId, UUID masterId, UUID salonId) {
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 5, NOW(), NOW())",
                UUID.randomUUID(), bookingId, clientId, masterId, salonId);
    }

    /**
     * A {@code MASTER}-scoped portfolio row against the OLD master, so case 5's
     * "no media is re-pointed at the new master" assertion has something it could fail on.
     *
     * <p><b>Why seeded and not uploaded.</b> {@code MediaService#resolvePortfolioTarget} emits
     * {@code EntityType.MASTER} only for an {@code INDEPENDENT_MASTER} ({@code :531-535}); a
     * {@code SALON_MASTER}'s uploads resolve to {@code SALON}. So there is no production write path
     * that could put this row here today, and without the seed the assertion read {@code 0 == 0} and
     * was structurally unable to go red. Seeding it arms the assertion now and keeps it armed if a
     * salon-master portfolio path is ever added.
     *
     * <p><b>Uploader is the OWNER, deliberately.</b> {@code media_files.uploader_id} is
     * {@code ON DELETE CASCADE} on {@code users} (V37), so a row uploaded by the staff member would
     * vanish with their account and prove nothing about re-pointing. The owner survives the cascade,
     * so the row survives on the detached stub — which is the state case 5 asserts against, in the
     * same shape as its {@code reviews} pair.
     *
     * <p>{@code PORTFOLIO} + {@code MASTER} satisfies {@code chk_media_files_media_type_entity_type};
     * the key and URL shapes satisfy {@code chk_media_files_r2_key_shape} and
     * {@code chk_media_files_r2_url_scheme} (all V39).
     */
    private void insertMasterPortfolioMedia(UUID uploaderId, UUID masterId) {
        UUID id = UUID.randomUUID();
        String key = "portfolio/masters/" + masterId + "/" + id + ".jpg";
        jdbcTemplate.update(
                "INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, "
                        + "r2_key, r2_url, created_at, updated_at) "
                        + "VALUES (?, ?, 'MASTER', ?, 'PORTFOLIO', ?, ?, NOW(), NOW())",
                id, uploaderId, masterId, key, "https://cdn.example/" + key);
    }

    /**
     * A live (unused, unexpired, unrevoked) invite row seeded directly — "stale" means exactly
     * that: a row in the table, not something the service can be talked into minting. Phase 287's
     * 409 makes a second dispatch for an already-registered address impossible through the API, so
     * the only honest way to represent the shape phase 295 step 3 exists to clean up is to insert
     * it.
     */
    private UUID insertPendingInvite(String email, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO invite_tokens (id, token, email, salon_id, role, expires_at, is_used, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'SALON_MASTER', NOW() + interval '48 hours', false, "
                        + "NOW(), NOW())",
                id, "stale-" + UUID.randomUUID(), email, salonId);
        return id;
    }

    // ── reads ───────────────────────────────────────────────────────────────────────────────

    private UUID userIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private UUID masterIdOfUser(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM masters WHERE user_id = ?", UUID.class, userId);
    }

    private String userRole(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId);
    }

    private UUID userSalonId(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT salon_id FROM users WHERE id = ?", UUID.class, userId);
    }

    private UUID masterUserId(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private UUID masterSalonId(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT salon_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    private int reviewCountOf(UUID masterId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT review_count FROM masters WHERE id = ?", Integer.class, masterId);
        return value == null ? -1 : value;
    }

    private BigDecimal avgRatingOf(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT avg_rating FROM masters WHERE id = ?", BigDecimal.class, masterId);
    }

    private int countUsersWithEmail(String email) {
        return count("SELECT COUNT(*) FROM users WHERE email = ?", email);
    }

    /** Live = the exact row set {@code ux_invite_tokens_active} covers. */
    private int countLiveInvites(String email, UUID salonId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_tokens WHERE lower(email) = lower(?) AND salon_id = ? "
                        + "AND is_used = false AND revoked_at IS NULL",
                Integer.class, email, salonId);
        return value == null ? -1 : value;
    }

    /**
     * Varargs since case 9, which needs {@code (id, salon_id)} together — an id-only predicate
     * would stay green if an unscoped sweep deleted the row and something else re-created it.
     */
    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }
}

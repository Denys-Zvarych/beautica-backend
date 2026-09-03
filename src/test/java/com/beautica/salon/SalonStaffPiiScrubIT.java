package com.beautica.salon;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.salon.dto.SalonInviteHistoryResponse;
import com.beautica.salon.dto.SalonInviteResponse;
import com.beautica.salon.service.SalonService;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Real-DB coverage for the Phase 291 extension of {@code SalonService#deactivateSalon}'s
 * salon-deletion staff cascade — the email-tombstone + PII scrub that closes the actual bug this
 * track exists for: "an owner deletes a salon, then cannot re-invite the master who was in it".
 * See {@code docs/backend-phases/phase-291-salon-deletion-staff-pii-scrub.md}.
 *
 * <p>Kept as a SEPARATE class from {@link SalonStaffDeactivationCascadeIT} (Phase 290) rather than
 * appended to it — that file's 8-case baseline stays an unchanged regression pin for the
 * masters/is_active/tokens/session-purge behaviour Phase 290 shipped; this file is the complete,
 * independent proof for everything Phase 291 adds on top of it. Fixtures are re-declared locally
 * (raw SQL inserts, mirroring {@link SalonStaffDeactivationCascadeIT}'s own house convention) —
 * this class cares about the STATE {@code deactivateSalon} leaves behind, not about how staff came
 * to exist.
 */
@DisplayName("SalonService.deactivateSalon — Phase 291 email tombstone + PII scrub")
class SalonStaffPiiScrubIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private SalonService salonService;

    @Autowired
    private InviteService inviteService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // Perf audit MEDIUM fix pinning (SpyBean wraps the REAL bean — end-state assertions
    // elsewhere in this class stay meaningful, this adds a statement-COUNT assertion on top,
    // mirroring SalonStaffDeactivationCascadeIT's Phase 290 precedent).
    @SpyBean
    private InviteTokenRepository inviteTokenRepository;

    /**
     * THE test this whole track exists for. Before this phase, {@code InviteService.sendInvite}
     * threw {@code EmailAlreadyRegisteredException} here because the scrubbed staff member's
     * ORIGINAL email was still sitting, untouched, on their deactivated {@code users} row.
     */
    @Test
    @DisplayName("THE BUG FIX — after the salon is deleted, sendInvite to the master's ORIGINAL "
            + "email succeeds instead of throwing EmailAlreadyRegisteredException")
    void should_allowReInvite_to_originalEmail_after_salonDeleted() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        // createUser (NOT createStaffUser — that helper appends its OWN nanoTime()+domain suffix
        // to whatever it is given, which would silently produce a garbled, never-matching email
        // here) so `originalEmail` is EXACTLY the address persisted and EXACTLY what gets
        // re-invited below.
        String originalEmail = "cascade-scrub-reinvite-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(originalEmail, "SALON_MASTER", salonId);
        createMasterRow(masterUserId, salonId, "SALON_MASTER");

        salonService.deactivateSalon(ownerId, salonId);

        // Deliberately no intermediate existsByEmail assertion here (that is
        // should_tombstoneEmail_when_salonDeactivated's job) — this test's ONLY assertion is that
        // sendInvite itself succeeds, so that removing the tombstone surfaces the mutation as the
        // EXACT failure mode this bug used to produce: an uncaught EmailAlreadyRegisteredException
        // out of sendInvite, not an earlier, unrelated assertion.
        //
        // The owner brings the master back under a NEW salon — the deleted salon stays
        // permanently inactive, a fresh one replaces it. This is the realistic re-invite path.
        UUID newSalonId = createSalon(ownerId);
        InviteRequest request = new InviteRequest(originalEmail, newSalonId, Role.SALON_MASTER);

        InviteResponse response = inviteService.sendInvite(request, ownerId);

        assertThat(response.invitedEmail()).isEqualTo(originalEmail);
    }

    @Test
    @DisplayName("email is rewritten to a non-routable, per-user tombstone at "
            + "@beautica-deleted.invalid, and existsByEmail(originalEmail) — the exact predicate "
            + "sendInvite/acceptInvite both gate on — is false")
    void should_tombstoneEmail_when_salonDeactivated() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        String originalEmail = "cascade-scrub-tomb-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(originalEmail, "SALON_MASTER", salonId);
        createMasterRow(masterUserId, salonId, "SALON_MASTER");

        salonService.deactivateSalon(ownerId, salonId);

        String email = emailOf(masterUserId);
        assertThat(email).endsWith("@beautica-deleted.invalid");
        assertThat(email).contains(masterUserId.toString());
        assertThat(userRepository.existsByEmail(originalEmail))
                .as("the scrubbed row's tombstone must free the original address")
                .isFalse();
    }

    @Test
    @DisplayName("every scrubbed PII column is null/reset; first/last name and avatarR2Key survive")
    void should_scrubEveryPiiColumn_butRetainNamesAndAvatarKey_when_salonDeactivated() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        UUID masterUserId = createStaffUser("cascade-scrub-full-", "SALON_MASTER", salonId);
        createMasterRow(masterUserId, salonId, "SALON_MASTER");
        seedFullPii(masterUserId);

        salonService.deactivateSalon(ownerId, salonId);

        assertThat(isNullColumn(masterUserId, "phone_number")).isTrue();
        assertThat(isNullColumn(masterUserId, "city")).isTrue();
        assertThat(isNullColumn(masterUserId, "region")).isTrue();
        assertThat(isNullColumn(masterUserId, "city_id")).isTrue();
        assertThat(isNullColumn(masterUserId, "district_id")).isTrue();
        assertThat(isNullColumn(masterUserId, "street")).isTrue();
        assertThat(isNullColumn(masterUserId, "building_no")).isTrue();
        assertThat(isNullColumn(masterUserId, "location_note")).isTrue();
        assertThat(isNullColumn(masterUserId, "avatar_url")).isTrue();
        assertThat(isNullColumn(masterUserId, "bio")).isTrue();
        assertThat(isNullColumn(masterUserId, "instagram")).isTrue();
        assertThat(isNullColumn(masterUserId, "professional_title")).isTrue();
        assertThat(isNullColumn(masterUserId, "business_name")).isTrue();
        assertThat(isNullColumn(masterUserId, "verification_code_hash")).isTrue();
        assertThat(isNullColumn(masterUserId, "verification_code_expires_at")).isTrue();
        assertThat(shortColumn(masterUserId, "verification_attempts")).isZero();
        assertThat(shortColumn(masterUserId, "verification_failed_total")).isZero();
        assertThat(isNullColumn(masterUserId, "verification_locked_until")).isTrue();
        assertThat(isNullColumn(masterUserId, "password_reset_code_hash")).isTrue();
        assertThat(isNullColumn(masterUserId, "password_reset_code_expires_at")).isTrue();
        assertThat(shortColumn(masterUserId, "password_reset_attempts")).isZero();
        assertThat(shortColumn(masterUserId, "password_reset_failed_total")).isZero();
        assertThat(isNullColumn(masterUserId, "password_reset_locked_until")).isTrue();

        assertThat(stringColumn(masterUserId, "first_name")).isEqualTo("Original");
        assertThat(stringColumn(masterUserId, "last_name")).isEqualTo("Master");
        assertThat(stringColumn(masterUserId, "avatar_r2_key"))
                .as("Phase 297's R2-cleanup handle — must survive Phase 291 untouched")
                .isEqualTo("avatars/original-key.jpg");

        assertThat(scrubbedAt(masterUserId)).isNotNull();
    }

    @Test
    @DisplayName("password_hash is rotated to a fresh real-BCrypt hash — the old password no "
            + "longer authenticates")
    void should_rotatePasswordHash_when_salonDeactivated() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        UUID masterUserId = createStaffUser("cascade-scrub-pwd-", "SALON_MASTER", salonId);
        createMasterRow(masterUserId, salonId, "SALON_MASTER");
        String oldHash = passwordHashOf(masterUserId);

        salonService.deactivateSalon(ownerId, salonId);

        String newHash = passwordHashOf(masterUserId);
        assertThat(newHash).isNotEqualTo(oldHash);
        assertThat(passwordEncoder.matches(TEST_PASSWORD, newHash))
                .as("the old password must no longer authenticate against the rotated hash")
                .isFalse();
        assertThat(newHash)
                .as("rotated hash must be real BCrypt — never a sentinel that trips "
                        + "BCryptPasswordEncoder's WARN log on a future login attempt")
                .startsWith("$2a$");
    }

    @Test
    @DisplayName("the owner's own row is untouched — email, PII, and password hash all survive "
            + "byte-identical")
    void should_leaveOwnerRow_untouched_when_salonDeactivated() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        UUID masterUserId = createStaffUser("cascade-scrub-owner-", "SALON_MASTER", salonId);
        createMasterRow(masterUserId, salonId, "SALON_MASTER");
        String ownerEmail = emailOf(ownerId);
        String ownerHash = passwordHashOf(ownerId);

        salonService.deactivateSalon(ownerId, salonId);

        assertThat(emailOf(ownerId)).isEqualTo(ownerEmail);
        assertThat(passwordHashOf(ownerId)).isEqualTo(ownerHash);
        assertThat(scrubbedAt(ownerId))
                .as("the owner's users row is NEVER scrubbed by this cascade")
                .isNull();
    }

    @Test
    @DisplayName("two staff scrubbed in the SAME deletion get distinct tombstones — no UNIQUE "
            + "violation on users.email")
    void should_generateDistinctTombstones_when_twoStaffScrubbedInOneDeletion() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        UUID masterAId = createStaffUser("cascade-scrub-dup-a-", "SALON_MASTER", salonId);
        createMasterRow(masterAId, salonId, "SALON_MASTER");
        UUID masterBId = createStaffUser("cascade-scrub-dup-b-", "SALON_MASTER", salonId);
        createMasterRow(masterBId, salonId, "SALON_MASTER");

        salonService.deactivateSalon(ownerId, salonId);

        assertThat(emailOf(masterAId)).isNotEqualTo(emailOf(masterBId));
        assertThat(scrubbedAt(masterAId)).isNotNull();
        assertThat(scrubbedAt(masterBId)).isNotNull();
    }

    @Test
    @DisplayName("a second DELETE on an already-inactive salon does not re-scrub — scrubbed_at, "
            + "the tombstone email and the password hash are all unchanged")
    void should_beIdempotent_when_salonDeactivatedTwice() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        UUID masterUserId = createStaffUser("cascade-scrub-idem-", "SALON_MASTER", salonId);
        createMasterRow(masterUserId, salonId, "SALON_MASTER");

        salonService.deactivateSalon(ownerId, salonId);
        Timestamp firstScrubbedAt = scrubbedAt(masterUserId);
        String firstEmail = emailOf(masterUserId);
        String firstHash = passwordHashOf(masterUserId);

        salonService.deactivateSalon(ownerId, salonId);

        assertThat(scrubbedAt(masterUserId))
                .as("a repeat DELETE must not re-stamp scrubbed_at — that would prove a re-scrub ran")
                .isEqualTo(firstScrubbedAt);
        assertThat(emailOf(masterUserId)).isEqualTo(firstEmail);
        assertThat(passwordHashOf(masterUserId)).isEqualTo(firstHash);
    }

    /**
     * Perf audit CRITICAL fix pin — {@code SalonService.SCRUBBED_PASSWORD_HASH} is a single
     * constant computed once per JVM, not once per staff member. Two staff scrubbed in the SAME
     * deletion must therefore receive the IDENTICAL {@code password_hash}, and it must still be a
     * real, valid BCrypt hash (never a sentinel — see that constant's javadoc for the
     * timing-side-channel reasoning) that does not authenticate either staff member's original
     * password.
     */
    @Test
    @DisplayName("two staff scrubbed in the SAME deletion receive the SAME shared password_hash — "
            + "the O(1) perf fix — and it is still real, valid BCrypt")
    void should_shareSameConstantPasswordHash_when_twoStaffScrubbedInOneDeletion() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        UUID masterAId = createStaffUser("cascade-scrub-const-a-", "SALON_MASTER", salonId);
        createMasterRow(masterAId, salonId, "SALON_MASTER");
        UUID masterBId = createStaffUser("cascade-scrub-const-b-", "SALON_MASTER", salonId);
        createMasterRow(masterBId, salonId, "SALON_MASTER");

        salonService.deactivateSalon(ownerId, salonId);

        String hashA = passwordHashOf(masterAId);
        String hashB = passwordHashOf(masterBId);
        assertThat(hashA)
                .as("the perf fix's whole point: ONE shared hash, not one BCrypt encode per staff member")
                .isEqualTo(hashB);
        assertThat(hashA)
                .as("must be a real, valid BCrypt hash — 60 chars, $2a$/$2b$ prefix")
                .matches("^\\$2[aby]\\$.{56}$")
                .hasSize(60);
        assertThat(passwordEncoder.matches(TEST_PASSWORD, hashA))
                .as("the shared hash must not authenticate either staff member's original password")
                .isFalse();
    }

    /**
     * Security audit MEDIUM fix pin — {@code invite_tokens.email} must be redacted for THIS
     * salon's dispatched invites once the recipient is scrubbed, otherwise the "irreversible" PII
     * scrub is trivially recoverable via {@code GET /salons/{salonId}/invites}.
     */
    @Test
    @DisplayName("invite_tokens.email for THIS salon's invite to the scrubbed staff member is "
            + "redacted to the SAME tombstone, and GET /salons/{salonId}/invites no longer "
            + "exposes the original address")
    void should_redactInviteTokenEmail_when_salonDeactivated() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        String originalEmail = "cascade-scrub-invite-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(originalEmail, "SALON_MASTER", salonId);
        createMasterRow(masterUserId, salonId, "SALON_MASTER");
        UUID inviteId = createInviteToken(originalEmail, salonId, "SALON_MASTER");

        salonService.deactivateSalon(ownerId, salonId);

        String tombstoneEmail = emailOf(masterUserId);
        assertThat(inviteTokenEmail(inviteId))
                .as("invite_tokens.email must be rewritten to the SAME tombstone the users row got")
                .isEqualTo(tombstoneEmail);

        SalonInviteHistoryResponse history = salonService.listSalonInvites(salonId);
        assertThat(history.invites())
                .extracting(SalonInviteResponse::recipientEmail)
                .as("the owner's own invite-history listing must not keep showing the original "
                        + "address after the recipient has been scrubbed")
                .doesNotContain(originalEmail)
                .contains(tombstoneEmail);
    }

    /**
     * Security audit LOW fix pin — exactly one INFO audit line per deletion, carrying the right
     * staff count, and never the tombstone email, the original email, or any other scrubbed
     * value.
     */
    @Test
    @DisplayName("exactly one audit-log INFO line is emitted per deletion, with the right staff "
            + "count, and it never contains an email address")
    void should_logAuditLineOnce_perDeletion_withCorrectCountAndNoEmail() {
        Logger salonServiceLogger = (Logger) LoggerFactory.getLogger(SalonService.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        salonServiceLogger.addAppender(logAppender);
        try {
            UUID ownerId = createOwner();
            UUID salonId = createSalon(ownerId);
            UUID masterAId = createStaffUser("cascade-scrub-log-a-", "SALON_MASTER", salonId);
            createMasterRow(masterAId, salonId, "SALON_MASTER");
            UUID masterBId = createStaffUser("cascade-scrub-log-b-", "SALON_MASTER", salonId);
            createMasterRow(masterBId, salonId, "SALON_MASTER");

            salonService.deactivateSalon(ownerId, salonId);

            List<ILoggingEvent> scrubLogs = logAppender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("PII scrub"))
                    .toList();
            assertThat(scrubLogs)
                    .as("exactly one audit line per deletion, not per staff member")
                    .hasSize(1);

            String message = scrubLogs.get(0).getFormattedMessage();
            assertThat(message).contains("2").contains(salonId.toString()).contains(ownerId.toString());
            assertThat(message)
                    .as("the audit line must never carry an email — tombstone, original, or otherwise")
                    .doesNotContain("@");
        } finally {
            salonServiceLogger.detachAppender(logAppender);
        }
    }

    /**
     * QA audit (2026-09-03) gap: {@code should_leaveOtherSalonsMasterUntouched_when_salonDeactivated}
     * in {@link SalonStaffDeactivationCascadeIT} (Phase 290) only asserts {@code is_active} flags
     * for a different salon's staff — nothing anywhere proved the Phase 291 PII scrub itself is
     * salon-scoped rather than platform-wide. A bug that scrubbed every {@code SALON_MASTER}/
     * {@code SALON_ADMIN} row regardless of {@code salonId} would pass every existing test in this
     * file (all of them seed exactly one salon) and only be caught here.
     */
    @Test
    @DisplayName("a DIFFERENT salon's staff member keeps their original email, PII, and password "
            + "hash byte-identical — the PII scrub is salon-scoped, not platform-wide")
    void should_leaveOtherSalonsStaffPii_untouched_when_salonDeactivated() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        UUID masterUserId = createStaffUser("cascade-scrub-target-", "SALON_MASTER", salonId);
        createMasterRow(masterUserId, salonId, "SALON_MASTER");

        UUID otherOwnerId = createOwner();
        UUID otherSalonId = createSalon(otherOwnerId);
        String otherOriginalEmail = "cascade-scrub-other-salon-" + System.nanoTime() + "@beautica.test";
        UUID otherMasterUserId = createUser(otherOriginalEmail, "SALON_MASTER", otherSalonId);
        createMasterRow(otherMasterUserId, otherSalonId, "SALON_MASTER");
        String otherHashBefore = passwordHashOf(otherMasterUserId);

        salonService.deactivateSalon(ownerId, salonId);

        assertThat(emailOf(otherMasterUserId))
                .as("a different salon's staff member's email must survive the scrub of an "
                        + "unrelated salon")
                .isEqualTo(otherOriginalEmail);
        assertThat(passwordHashOf(otherMasterUserId)).isEqualTo(otherHashBefore);
        assertThat(scrubbedAt(otherMasterUserId))
                .as("scrubbed_at must stay null for staff outside the deleted salon")
                .isNull();
        assertThat(userRepository.existsByEmail(otherOriginalEmail)).isTrue();
    }

    /**
     * QA audit (2026-09-03) gap: {@code should_redactInviteTokenEmail_when_salonDeactivated} only
     * proves the redaction fires for THIS salon's own invite — it never proves the redaction
     * respects its {@code salonId} predicate rather than matching on {@code email} alone. A
     * regression that dropped the {@code AND t.salonId = :salonId} clause from
     * {@link com.beautica.user.InviteTokenRepository#redactEmailBySalonIdAndEmail} would pass
     * every existing test in this class and only be caught here.
     */
    @Test
    @DisplayName("a DIFFERENT salon's PENDING invite to the SAME email the scrubbed master used is "
            + "left untouched — invite_tokens redaction is scoped to the deleting salon only")
    void should_preserveUnrelatedSalonsPendingInvite_when_salonDeactivated() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        String originalEmail = "cascade-scrub-scope-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(originalEmail, "SALON_MASTER", salonId);
        createMasterRow(masterUserId, salonId, "SALON_MASTER");
        UUID thisSalonInviteId = createInviteToken(originalEmail, salonId, "SALON_MASTER");

        UUID otherOwnerId = createOwner();
        UUID otherSalonId = createSalon(otherOwnerId);
        UUID unrelatedInviteId = createInviteToken(originalEmail, otherSalonId, "SALON_MASTER");

        salonService.deactivateSalon(ownerId, salonId);

        String tombstoneEmail = emailOf(masterUserId);
        assertThat(inviteTokenEmail(thisSalonInviteId))
                .as("this salon's own invite must still be redacted")
                .isEqualTo(tombstoneEmail);
        assertThat(inviteTokenEmail(unrelatedInviteId))
                .as("a different salon's own pending invite to the same address must survive "
                        + "untouched — it belongs to that salon's own history")
                .isEqualTo(originalEmail);
    }

    /**
     * QA audit (2026-09-03) gap: {@code should_shareSameConstantPasswordHash_when_
     * twoStaffScrubbedInOneDeletion} only proves sharing WITHIN one batch — that would also pass
     * if a regression reintroduced per-DELETION (not per-JVM) hash generation that happened to be
     * stable within a single call but varied across calls. This proves the constant is scoped to
     * the JVM/class, not to the deletion transaction.
     */
    @Test
    @DisplayName("staff scrubbed in TWO SEPARATE deletions still receive the SAME constant "
            + "password_hash — proves the constant is JVM-scoped, not batch/deletion-scoped")
    void should_shareSameConstantPasswordHash_acrossSeparateDeletions() {
        UUID ownerAId = createOwner();
        UUID salonAId = createSalon(ownerAId);
        UUID masterAId = createStaffUser("cascade-scrub-xdel-a-", "SALON_MASTER", salonAId);
        createMasterRow(masterAId, salonAId, "SALON_MASTER");

        salonService.deactivateSalon(ownerAId, salonAId);
        String hashFromFirstDeletion = passwordHashOf(masterAId);

        UUID ownerBId = createOwner();
        UUID salonBId = createSalon(ownerBId);
        UUID masterBId = createStaffUser("cascade-scrub-xdel-b-", "SALON_MASTER", salonBId);
        createMasterRow(masterBId, salonBId, "SALON_MASTER");

        salonService.deactivateSalon(ownerBId, salonBId);
        String hashFromSecondDeletion = passwordHashOf(masterBId);

        assertThat(hashFromSecondDeletion)
                .as("the shared hash constant must be identical across independent "
                        + "deactivateSalon calls, not merely within one batch")
                .isEqualTo(hashFromFirstDeletion);
    }

    /**
     * Perf audit MEDIUM fix pin — {@code invite_tokens.email} redaction must be exactly ONE bulk
     * statement for the whole staff cascade, not one {@code @Modifying} UPDATE per staff member.
     * A regression to the per-staff-member loop passes every OTHER test in this class (they only
     * assert end state, e.g. {@code inviteTokenEmail(inviteId) == tombstoneEmail}), which is
     * exactly why this needs its own statement-count assertion — see
     * {@code InviteTokenRepository#redactEmailsBySalonIdAndStaffUserIds}.
     */
    @Test
    @DisplayName("N staff members, each with their own dispatched invite — email redaction is "
            + "exactly ONE bulk statement, never one per staff member")
    void should_issueExactlyOneRedactionStatement_regardlessOfStaffCount() {
        UUID ownerId = createOwner();
        UUID salonId = createSalon(ownerId);
        String emailA = "cascade-scrub-stmtcount-a-" + System.nanoTime() + "@beautica.test";
        UUID masterAId = createUser(emailA, "SALON_MASTER", salonId);
        createMasterRow(masterAId, salonId, "SALON_MASTER");
        createInviteToken(emailA, salonId, "SALON_MASTER");
        String emailB = "cascade-scrub-stmtcount-b-" + System.nanoTime() + "@beautica.test";
        UUID masterBId = createUser(emailB, "SALON_MASTER", salonId);
        createMasterRow(masterBId, salonId, "SALON_MASTER");
        createInviteToken(emailB, salonId, "SALON_MASTER");
        String emailC = "cascade-scrub-stmtcount-c-" + System.nanoTime() + "@beautica.test";
        UUID masterCId = createUser(emailC, "SALON_MASTER", salonId);
        createMasterRow(masterCId, salonId, "SALON_MASTER");
        createInviteToken(emailC, salonId, "SALON_MASTER");

        salonService.deactivateSalon(ownerId, salonId);

        verify(inviteTokenRepository, times(1))
                .redactEmailsBySalonIdAndStaffUserIds(eq(salonId), any(List.class), anyString(), anyString());

        // End-state sanity — the ONE bulk statement actually redacted all three, not zero-row.
        assertThat(emailOf(masterAId)).startsWith("deleted+");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private UUID createOwner() {
        return createUser("cascade-scrub-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER", null);
    }

    private UUID createSalon(UUID ownerId) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());
        return salonId;
    }

    private UUID createStaffUser(String emailPrefix, String role, UUID salonId) {
        return createUser(emailPrefix + System.nanoTime() + "@beautica.test", role, salonId);
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, ?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId, "Original", "Master");
        return id;
    }

    private UUID createMasterRow(UUID userId, UUID salonId, String masterType) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, true, NOW(), NOW())",
                masterId, userId, salonId, masterType);
        return masterId;
    }

    /** Bare-bones {@code invite_tokens} row — mirrors this class's own house convention of raw
     * SQL inserts rather than routing through {@link InviteService}, which would dispatch a real
     * email. {@code token} only needs to satisfy the column's {@code UNIQUE} constraint. */
    private UUID createInviteToken(String email, UUID salonId, String role) {
        UUID inviteId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO invite_tokens (id, token, email, salon_id, role, expires_at, is_used) "
                        + "VALUES (?, ?, ?, ?, ?, NOW() + interval '3 days', false)",
                inviteId, "token-" + inviteId, email, salonId, role);
        return inviteId;
    }

    /** Seeds every scrubbable column with a real, non-null value so the scrub test proves the
     * mutation MOVED each value, rather than merely finding a column that was already null. */
    private void seedFullPii(UUID userId) {
        UUID districtId = jdbcTemplate.queryForObject("SELECT id FROM city_districts LIMIT 1", UUID.class);
        jdbcTemplate.update(
                "UPDATE users SET phone_number = ?, city = ?, region = ?, city_id = ?, district_id = ?, "
                        + "street = ?, building_no = ?, location_note = ?, avatar_url = ?, avatar_r2_key = ?, "
                        + "bio = ?, instagram = ?, professional_title = ?, business_name = ?, "
                        + "verification_code_hash = ?, verification_code_expires_at = NOW() + interval '1 hour', "
                        + "verification_attempts = 3, verification_failed_total = 5, "
                        + "verification_locked_until = NOW() + interval '1 hour', "
                        + "password_reset_code_hash = ?, "
                        + "password_reset_code_expires_at = NOW() + interval '1 hour', "
                        + "password_reset_attempts = 2, password_reset_failed_total = 4, "
                        + "password_reset_locked_until = NOW() + interval '1 hour' "
                        + "WHERE id = ?",
                "+380501112233", "Kyiv", "Kyiv Oblast", testCityId(), districtId,
                "Khreshchatyk 1", "1A", "Ring the bell", "https://cdn.example.com/avatar.jpg",
                "avatars/original-key.jpg", "Experienced master", "@originalmaster", "Nail master",
                "Original Studio", "a".repeat(64), "b".repeat(64), userId);
    }

    // ── assertions ──────────────────────────────────────────────────────────────────────────

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private String inviteTokenEmail(UUID inviteId) {
        return jdbcTemplate.queryForObject(
                "SELECT email FROM invite_tokens WHERE id = ?", String.class, inviteId);
    }

    private String passwordHashOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?", String.class, userId);
    }

    private Timestamp scrubbedAt(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT scrubbed_at FROM users WHERE id = ?", Timestamp.class, userId);
    }

    private boolean isNullColumn(UUID userId, String column) {
        Boolean isNull = jdbcTemplate.queryForObject(
                "SELECT (" + column + " IS NULL) FROM users WHERE id = ?", Boolean.class, userId);
        return Boolean.TRUE.equals(isNull);
    }

    private String stringColumn(UUID userId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM users WHERE id = ?", String.class, userId);
    }

    private short shortColumn(UUID userId, String column) {
        Short value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM users WHERE id = ?", Short.class, userId);
        return value == null ? 0 : value;
    }
}

package com.beautica.salon;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.location.repository.CityRepository;
import com.beautica.location.service.LocationQueryService;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.dto.SalonInviteHistoryResponse;
import com.beautica.salon.dto.SalonInviteResponse;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.user.InviteHistoryRow;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.RevocationReason;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SalonService#listSalonInvites} and {@link SalonService#cancelInvite}.
 *
 * <p><strong>The reason this class exists rather than leaning on the integration test.</strong>
 * {@code SalonInviteResponse#from} classifies a row through a FIVE-STEP LADDER whose ORDER is
 * load-bearing, because {@code is_used = true} is written by BOTH acceptance and cancellation.
 * An integration test that seeds an accepted row and a cancelled row separately passes with the
 * two checks in either order — each row only ever satisfies one of them. The defect is only
 * observable on a row that satisfies BOTH at once, which is precisely what
 * {@link #should_classifyCancelled_when_rowIsBothUsedAndCancelled()} constructs. That test is the
 * point of this file; the rest is ordinary wiring and cancel-guard coverage.
 *
 * <p>Salon-scoping ("is the caller the owner or an admin of {@code salonId}") is already enforced
 * by {@code @PreAuthorize("... and @authz.canManageSalon(authentication, #salonId)")} on
 * {@code SalonController} — mirrors {@code SalonServiceRemoveAdminTest}'s rationale. These tests
 * cover the behaviour that IS expressible at the service layer: the repository query wiring, the
 * page cap, status derivation, and the defense-in-depth cross-salon + already-used checks in
 * {@code cancelInvite}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalonService — salon invite history — unit")
class SalonServiceInviteHistoryTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-29T12:00:00Z");

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private InviteService inviteService;

    @Mock
    private InviteTokenRepository inviteTokenRepository;

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private MasterServiceRepository masterServiceRepository;

    @Mock
    private LocalityWriteValidator localityWriteValidator;

    @Mock
    private MasterService masterService;

    @Mock
    private CityRepository cityRepository;

    @Mock
    private LocationQueryService locationQueryService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private AuthorizationService authorizationService;

    // Audit-fix cycle 2: SalonService now evicts the user-profile cache on the three paths
    // that write a `users` row (createSalon locality sync, removeAdmin, rotateAdmin). None is
    // exercised by this class, so a real evictor over a NoOpCacheManager is enough.
    private final com.beautica.common.cache.UserProfileCacheEvictor userProfileCacheEvictor =
            new com.beautica.common.cache.UserProfileCacheEvictor(
                    new org.springframework.cache.support.NoOpCacheManager());

    private SalonService salonService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        salonService = new SalonService(
                salonRepository, userRepository, inviteService, inviteTokenRepository, masterRepository,
                masterServiceRepository, localityWriteValidator, masterService, cityRepository,
                locationQueryService, cacheManager, authorizationService, fixedClock,
                userProfileCacheEvictor);
    }

    // ── the derivation ladder ─────────────────────────────────────────────────

    /**
     * THE ladder-order guard. Every cancelled invite is BOTH {@code is_used = true} (kept so
     * every downstream "already consumed?" guard is unchanged) AND
     * {@code revoked_reason = CANCELLED}. Swapping the first two rungs of
     * {@code SalonInviteResponse#from} — testing {@code used()} before the CANCELLED marker —
     * silently relabels EVERY cancellation in every salon's history as "Accepted", the single
     * most consequential thing this feature can get wrong. No integration test that seeds an
     * accepted row and a cancelled row separately can see it.
     *
     * <p>The row is hand-built rather than derived from {@code InviteToken#markCancelled} because
     * the history query now returns an {@link InviteHistoryRow} PROJECTION, not the entity (the
     * token hash is never fetched on this path). That the used/CANCELLED collision is REAL — that
     * {@code markCancelled} genuinely writes both — is pinned separately, against production code,
     * by {@link #should_markTokenCancelled_when_cancellingAPendingInvite()}. The two tests are a
     * pair: this one proves the ladder survives the collision, that one proves the collision
     * exists.
     */
    @Test
    @DisplayName("listSalonInvites — a row that is BOTH used AND revoked-as-CANCELLED classifies "
            + "CANCELLED, never ACCEPTED (pins the ladder ORDER, not just its outputs)")
    void should_classifyCancelled_when_rowIsBothUsedAndCancelled() {
        UUID salonId = UUID.randomUUID();
        InviteHistoryRow row = row("cancelled@beautica.test", Role.SALON_MASTER, true,
                RevocationReason.CANCELLED, FIXED_NOW.plusSeconds(3600), FIXED_NOW);

        stubHistory(salonId, row);

        SalonInviteHistoryResponse result = salonService.listSalonInvites(salonId);

        assertThat(result.invites()).singleElement()
                .extracting(SalonInviteResponse::status)
                .as("a cancelled invite is is_used=true AND revoked_reason=CANCELLED at the same "
                        + "time; if the used() rung is checked first it wins and the owner is told "
                        + "the invite they revoked was ACCEPTED")
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("listSalonInvites — a used, never-revoked row classifies ACCEPTED")
    void should_classifyAccepted_when_rowIsUsedAndNotRevoked() {
        UUID salonId = UUID.randomUUID();
        stubHistory(salonId, row("accepted@beautica.test", Role.SALON_ADMIN, true, null,
                FIXED_NOW.plusSeconds(3600), FIXED_NOW));

        assertThat(salonService.listSalonInvites(salonId).invites()).singleElement()
                .extracting(SalonInviteResponse::status)
                .isEqualTo("ACCEPTED");
    }

    /**
     * The SUPERSEDED rung sits BEFORE the clock comparison on purpose. A superseded row is in
     * practice always already past its {@code expiresAt} (only an expired token is ever
     * superseded), so this test deliberately gives it a FUTURE {@code expiresAt} — the only
     * shape that can tell the two rungs apart. With the SUPERSEDED rung removed the row would
     * fall through to PENDING and reappear in the UI as a live invite whose link is dead.
     */
    @Test
    @DisplayName("listSalonInvites — a SUPERSEDED row classifies EXPIRED even while its expiresAt "
            + "is still in the future (the SUPERSEDED rung precedes the clock rung)")
    void should_classifyExpired_when_rowIsSupersededWithFutureExpiry() {
        UUID salonId = UUID.randomUUID();
        stubHistory(salonId, row("superseded@beautica.test", Role.SALON_MASTER, false,
                RevocationReason.SUPERSEDED, FIXED_NOW.plusSeconds(3600), FIXED_NOW));

        assertThat(salonService.listSalonInvites(salonId).invites()).singleElement()
                .extracting(SalonInviteResponse::status)
                .isEqualTo("EXPIRED");
    }

    /**
     * Guards the {@code used = false} half of the SUPERSEDED contract at its source: if
     * {@code markSuperseded} ever started flipping {@code isUsed}, the projection would carry
     * {@code used = true} and rung 2 would relabel every superseded invite ACCEPTED before the
     * SUPERSEDED rung was ever reached. The hand-built row above cannot see that.
     */
    @Test
    @DisplayName("InviteToken#markSuperseded records the reason WITHOUT flipping isUsed (the "
            + "precondition the SUPERSEDED rung depends on)")
    void should_keepIsUsedFalse_when_markingSuperseded() {
        InviteToken token = buildToken(UUID.randomUUID(), "superseded@beautica.test",
                UUID.randomUUID(), Role.SALON_MASTER, FIXED_NOW.plusSeconds(3600), FIXED_NOW);

        token.markSuperseded(FIXED_NOW);

        assertThat(token.isUsed())
                .as("the invite was never accepted — a true here would make the history report it "
                        + "ACCEPTED, because rung 2 precedes the SUPERSEDED rung")
                .isFalse();
        assertThat(token.getRevokedReason()).isEqualTo(RevocationReason.SUPERSEDED);
    }

    @Test
    @DisplayName("listSalonInvites — an unused, unrevoked row whose expiresAt has passed classifies EXPIRED")
    void should_classifyExpired_when_rowLapsedOnItsOwn() {
        UUID salonId = UUID.randomUUID();
        stubHistory(salonId, row("lapsed@beautica.test", Role.SALON_MASTER, false, null,
                FIXED_NOW.minusSeconds(1), FIXED_NOW.minusSeconds(7200)));

        assertThat(salonService.listSalonInvites(salonId).invites()).singleElement()
                .extracting(SalonInviteResponse::status)
                .isEqualTo("EXPIRED");
    }

    /**
     * Boundary: {@code expiresAt == now} is EXPIRED, not PENDING — the production rung delegates
     * to {@code InviteToken#isExpired}, which is {@code !expiresAt.isAfter(now)}. This is the
     * SINGLE canonical predicate; {@link #should_agreeWithAcceptPath_when_expiresAtEqualsNow()}
     * pins that the accept path reads the same instant the same way, which is the drift this
     * boundary used to have.
     */
    @Test
    @DisplayName("listSalonInvites — expiresAt exactly equal to now classifies EXPIRED, not PENDING")
    void should_classifyExpired_when_expiresAtEqualsNowExactly() {
        UUID salonId = UUID.randomUUID();
        stubHistory(salonId, row("boundary@beautica.test", Role.SALON_MASTER, false, null,
                FIXED_NOW, FIXED_NOW.minusSeconds(60)));

        assertThat(salonService.listSalonInvites(salonId).invites()).singleElement()
                .extracting(SalonInviteResponse::status)
                .isEqualTo("EXPIRED");
    }

    /**
     * The anti-drift guard for the shared predicate. Before it was extracted, the display ladder
     * used {@code !isAfter(now)} while {@code InviteService#previewInvite}/{@code acceptInvite}
     * and {@code InvitePersistenceService}'s recycle filter used {@code isBefore(now)}: for the
     * one instant {@code now == expiresAt} the owner was shown EXPIRED while the link still
     * provisioned an account. Asserting the two sides AGREE — rather than asserting each side's
     * literal — is what makes re-opening the gap impossible without turning this red.
     */
    @Test
    @DisplayName("the history ladder and the accept path agree at expiresAt == now (one shared "
            + "predicate, no boundary drift)")
    void should_agreeWithAcceptPath_when_expiresAtEqualsNow() {
        UUID salonId = UUID.randomUUID();
        stubHistory(salonId, row("boundary@beautica.test", Role.SALON_MASTER, false, null,
                FIXED_NOW, FIXED_NOW.minusSeconds(60)));
        InviteToken sameToken = buildToken(UUID.randomUUID(), "boundary@beautica.test", salonId,
                Role.SALON_MASTER, FIXED_NOW, FIXED_NOW.minusSeconds(60));

        String displayedStatus = salonService.listSalonInvites(salonId).invites().getFirst().status();

        assertThat(sameToken.isExpiredAt(FIXED_NOW))
                .as("the accept/preview/recycle predicate must call this token dead at exactly the "
                        + "instant the owner is shown '%s'", displayedStatus)
                .isEqualTo("EXPIRED".equals(displayedStatus));
    }

    @Test
    @DisplayName("listSalonInvites — expiresAt one second after now classifies PENDING (the other "
            + "side of the same boundary)")
    void should_classifyPending_when_expiresAtIsOneSecondAfterNow() {
        UUID salonId = UUID.randomUUID();
        stubHistory(salonId, row("live@beautica.test", Role.SALON_MASTER, false, null,
                FIXED_NOW.plusSeconds(1), FIXED_NOW.minusSeconds(60)));

        assertThat(salonService.listSalonInvites(salonId).invites()).singleElement()
                .extracting(SalonInviteResponse::status)
                .isEqualTo("PENDING");
    }

    // ── mapping, cap, truncation, and query wiring ────────────────────────────

    @Test
    @DisplayName("listSalonInvites maps every field of a pending row and exposes no token material")
    void should_returnMappedResponses_when_invitesExist() {
        UUID salonId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        Instant createdAt = FIXED_NOW.minusSeconds(120);
        InviteHistoryRow row = new InviteHistoryRow(inviteId, "invitee@beautica.test",
                Role.SALON_MASTER, false, null, FIXED_NOW.plusSeconds(3600), createdAt);

        stubHistory(salonId, row);

        SalonInviteHistoryResponse result = salonService.listSalonInvites(salonId);

        assertThat(result.invites()).singleElement()
                .extracting(SalonInviteResponse::inviteId, SalonInviteResponse::recipientEmail,
                        SalonInviteResponse::role, SalonInviteResponse::status,
                        SalonInviteResponse::createdAt, SalonInviteResponse::expiresAt)
                .containsExactly(inviteId, "invitee@beautica.test", "SALON_MASTER", "PENDING",
                        createdAt, FIXED_NOW.plusSeconds(3600));

        // Structural, not stylistic: the record's component set IS the wire shape, so a future
        // component carrying the token hash would have to trip this.
        assertThat(SalonInviteResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .as("SalonInviteResponse must never gain a token/hash component — invite_tokens.token "
                        + "is a SHA-256 digest of a live credential")
                .containsExactlyInAnyOrder("inviteId", "recipientEmail", "role", "status",
                        "createdAt", "expiresAt");
    }

    /**
     * The projection is what keeps {@code invite_tokens.token} off this read path entirely. A
     * future "just switch it back to the entity, it's simpler" refactor would silently re-fetch a
     * SHA-256 digest of a live credential for every one of 200 rows; this pins the shape so that
     * cannot happen unnoticed.
     */
    @Test
    @DisplayName("the invite-history projection carries no token component at all")
    void should_carryNoTokenColumn_when_projectingInviteHistory() {
        assertThat(InviteHistoryRow.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .as("InviteHistoryRow is the read-path projection — a token/hash component here "
                        + "would put credential material back in the heap for every history read")
                .containsExactlyInAnyOrder("id", "email", "role", "used", "revokedReason",
                        "expiresAt", "createdAt");
    }

    /**
     * The page size is deliberately {@code MAX_INVITE_HISTORY + 1}, not {@code MAX_INVITE_HISTORY}:
     * the surplus row is the truncation PROBE. Asking for exactly the cap would make a
     * full-to-the-brim page indistinguishable from a truncated one, which is the whole defect
     * {@code SalonInviteHistoryResponse.truncated} exists to fix.
     */
    @Test
    @DisplayName("listSalonInvites requests one MAX_INVITE_HISTORY + 1 page (the cap plus the "
            + "truncation probe row)")
    void should_requestOneCapPlusProbePage_when_listingSalonInvites() {
        UUID salonId = UUID.randomUUID();
        when(inviteTokenRepository.findSalonInviteHistory(eq(salonId), any(Pageable.class)))
                .thenReturn(List.of());

        salonService.listSalonInvites(salonId);

        var pageable = forClass(Pageable.class);
        verify(inviteTokenRepository).findSalonInviteHistory(eq(salonId), pageable.capture());
        assertThat(pageable.getValue())
                .as("the unbounded-collection guard (Anti-Bug §E3) requests the 200-row cap plus "
                        + "ONE probe row, so truncation is detectable without a COUNT round trip")
                .isEqualTo(PageRequest.of(0, 201));
    }

    @Test
    @DisplayName("listSalonInvites reports truncated=false and returns every row when the salon is "
            + "exactly at the cap")
    void should_reportNotTruncated_when_rowCountEqualsTheCap() {
        UUID salonId = UUID.randomUUID();
        stubHistory(salonId, rows(200));

        SalonInviteHistoryResponse result = salonService.listSalonInvites(salonId);

        assertThat(result.truncated())
                .as("200 rows is the cap EXACTLY — nothing was dropped, so the client must not be "
                        + "told its audit trail is incomplete")
                .isFalse();
        assertThat(result.invites()).hasSize(200);
    }

    /**
     * The S5 signal. Before the flag, a salon past the cap was handed a silently incomplete audit
     * trail with no way to tell it apart from a complete one — on the endpoint whose entire
     * purpose is being the audit trail.
     */
    @Test
    @DisplayName("listSalonInvites reports truncated=true and still returns only the cap when the "
            + "salon has more invites than the cap")
    void should_reportTruncated_when_rowCountExceedsTheCap() {
        UUID salonId = UUID.randomUUID();
        stubHistory(salonId, rows(201));

        SalonInviteHistoryResponse result = salonService.listSalonInvites(salonId);

        assertThat(result.truncated())
                .as("the 201st row came back, so older invites exist beyond the cap and the client "
                        + "must be told the history it is rendering is partial")
                .isTrue();
        assertThat(result.invites())
                .as("the probe row must be DROPPED, not returned — the cap is still the contract")
                .hasSize(200);
        assertThat(result.invites().getLast().recipientEmail())
                .as("the row dropped is the OLDEST (the probe), so the newest 200 survive intact")
                .isEqualTo("invite-199@beautica.test");
    }

    /**
     * The repository returns rows already ordered {@code created_at DESC, id DESC}; the service
     * must not re-sort, re-reverse, or otherwise reshuffle them. Ordering ITSELF is proven
     * against real SQL in {@code SalonInviteHistoryIntegrationTest} — what this asserts is the
     * narrower service-layer property that the stream mapping is order-preserving, which a
     * {@code Collectors.toMap}/{@code Set} refactor would silently break.
     */
    @Test
    @DisplayName("listSalonInvites preserves the repository's row order verbatim (no client-side re-sort)")
    void should_preserveRepositoryOrder_when_mappingHistory() {
        UUID salonId = UUID.randomUUID();
        stubHistory(salonId,
                row("newest@beautica.test", Role.SALON_MASTER, false, null,
                        FIXED_NOW.plusSeconds(3600), FIXED_NOW.minusSeconds(10)),
                row("middle@beautica.test", Role.SALON_MASTER, false, null,
                        FIXED_NOW.plusSeconds(3600), FIXED_NOW.minusSeconds(1000)),
                row("oldest@beautica.test", Role.SALON_MASTER, false, null,
                        FIXED_NOW.plusSeconds(3600), FIXED_NOW.minusSeconds(100000)));

        assertThat(salonService.listSalonInvites(salonId).invites())
                .extracting(SalonInviteResponse::recipientEmail)
                .containsExactly("newest@beautica.test", "middle@beautica.test", "oldest@beautica.test");
    }

    @Test
    @DisplayName("listSalonInvites returns an empty list, not truncated, when the salon has never "
            + "invited anyone")
    void should_returnEmptyList_when_noInvitesExist() {
        UUID salonId = UUID.randomUUID();
        stubHistory(salonId);

        SalonInviteHistoryResponse result = salonService.listSalonInvites(salonId);

        assertThat(result.invites()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    // ── cancelInvite ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelInvite marks the token used=true AND revoked CANCELLED rather than deleting the row")
    void should_markTokenCancelled_when_cancellingAPendingInvite() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        InviteToken token = buildToken(inviteId, "invitee@beautica.test", salonId, Role.SALON_MASTER,
                FIXED_NOW.plusSeconds(3600), FIXED_NOW);

        when(inviteTokenRepository.findByIdForUpdate(inviteId)).thenReturn(Optional.of(token));

        salonService.cancelInvite(actorId, salonId, inviteId);

        assertThat(token.isUsed())
                .as("cancelInvite must keep marking the token used — every downstream "
                        + "already-consumed guard (accept, preview, double-cancel) reads that flag")
                .isTrue();
        assertThat(token.getRevokedReason())
                .as("without the CANCELLED marker the history endpoint would report this as ACCEPTED")
                .isEqualTo(RevocationReason.CANCELLED);
        assertThat(token.getRevokedAt())
                .as("revokedAt must come from the injected Clock, never a bare Instant.now() (§G)")
                .isEqualTo(FIXED_NOW);
        verify(inviteTokenRepository, never()).delete(any());
    }

    @Test
    @DisplayName("cancelInvite throws NotFoundException when the invite id does not exist")
    void should_throwNotFound_when_inviteDoesNotExist() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID missingInviteId = UUID.randomUUID();

        when(inviteTokenRepository.findByIdForUpdate(missingInviteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salonService.cancelInvite(actorId, salonId, missingInviteId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Invite not found");
    }

    @Test
    @DisplayName("cancelInvite throws NotFoundException (defense-in-depth) when the token belongs to a different salon")
    void should_throwNotFound_when_tokenBelongsToDifferentSalon() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID otherSalonId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        InviteToken token = buildToken(inviteId, "invitee@beautica.test", otherSalonId, Role.SALON_MASTER,
                FIXED_NOW.plusSeconds(3600), FIXED_NOW);

        when(inviteTokenRepository.findByIdForUpdate(inviteId)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> salonService.cancelInvite(actorId, salonId, inviteId))
                .isInstanceOf(NotFoundException.class);
        assertThat(token.isUsed())
                .as("a cross-salon token must never be mutated")
                .isFalse();
        assertThat(token.getRevokedAt())
                .as("a cross-salon token must not be revoked either — the guard runs before markCancelled")
                .isNull();
    }

    @Test
    @DisplayName("cancelInvite throws NotFoundException when the token is already used")
    void should_throwNotFound_when_tokenAlreadyUsed() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        InviteToken token = buildToken(inviteId, "invitee@beautica.test", salonId, Role.SALON_MASTER,
                FIXED_NOW.plusSeconds(3600), FIXED_NOW);
        token.markUsed();

        when(inviteTokenRepository.findByIdForUpdate(inviteId)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> salonService.cancelInvite(actorId, salonId, inviteId))
                .isInstanceOf(NotFoundException.class);
        assertThat(token.getRevokedReason())
                .as("an already-accepted invite must not be retro-labelled CANCELLED by a late "
                        + "cancel attempt — the isUsed guard runs before markCancelled")
                .isNull();
    }

    /**
     * S1. A SUPERSEDED row keeps {@code is_used = false}, so the original {@code isUsed()}-only
     * guard let it through — and {@code markCancelled} OVERWRITES {@code revoked_at} and flips
     * {@code revoked_reason} SUPERSEDED &rarr; CANCELLED. An owner could therefore rewrite the
     * recorded outcome of an invite nobody ever cancelled, on the endpoint that IS the audit
     * trail. The row must be untouched and the call must 404, indistinguishably from "no such
     * invite".
     */
    @Test
    @DisplayName("cancelInvite returns 404 for a SUPERSEDED invite and leaves its recorded outcome "
            + "intact (history rows are never rewritten)")
    void should_return404_when_cancellingSupersededInvite() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        Instant supersededAt = FIXED_NOW.minusSeconds(600);
        InviteToken token = buildToken(inviteId, "invitee@beautica.test", salonId, Role.SALON_MASTER,
                FIXED_NOW.plusSeconds(3600), FIXED_NOW.minusSeconds(7200));
        token.markSuperseded(supersededAt);

        assertThat(token.isUsed())
                .as("precondition — a superseded row keeps is_used = false, which is exactly why "
                        + "the old isUsed()-only guard did not stop this")
                .isFalse();

        when(inviteTokenRepository.findByIdForUpdate(inviteId)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> salonService.cancelInvite(actorId, salonId, inviteId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Invite not found");
        assertThat(token.getRevokedReason())
                .as("SUPERSEDED must not be rewritten to CANCELLED — that falsifies the audit trail")
                .isEqualTo(RevocationReason.SUPERSEDED);
        assertThat(token.getRevokedAt())
                .as("the original revocation instant must survive untouched")
                .isEqualTo(supersededAt);
    }

    /**
     * S1, the other half. A row that simply lapsed carries {@code is_used = false} and
     * {@code revoked_at = null}, so it too sailed past the old guard. Cancelling a dead invite
     * changes nothing about reality but would stamp it CANCELLED, hiding that it was never acted
     * on. Uses the shared {@code InviteToken#isExpired} predicate, so this boundary can never
     * drift from the accept path's.
     */
    @Test
    @DisplayName("cancelInvite returns 404 for an invite that has simply expired, and does not "
            + "relabel it CANCELLED")
    void should_return404_when_cancellingExpiredInvite() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        InviteToken token = buildToken(inviteId, "invitee@beautica.test", salonId, Role.SALON_MASTER,
                FIXED_NOW.minusSeconds(1), FIXED_NOW.minusSeconds(7200));

        when(inviteTokenRepository.findByIdForUpdate(inviteId)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> salonService.cancelInvite(actorId, salonId, inviteId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Invite not found");
        assertThat(token.getRevokedReason())
                .as("an invite that lapsed on its own was never cancelled by anyone; recording it "
                        + "as CANCELLED misattributes the outcome to the owner")
                .isNull();
        assertThat(token.isUsed())
                .as("nor may it be marked consumed")
                .isFalse();
    }

    /**
     * The exact instant {@code expiresAt == now} is on the DEAD side of the shared predicate, so
     * cancel refuses it — the same instant at which {@code acceptInvite} refuses it. Without a
     * shared predicate the two could disagree here, leaving a one-instant window where an invite
     * was neither cancellable nor acceptable (or worse, both).
     */
    @Test
    @DisplayName("cancelInvite returns 404 at exactly expiresAt == now (same boundary the accept "
            + "path uses)")
    void should_return404_when_cancellingAtTheExpiryBoundary() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        InviteToken token = buildToken(inviteId, "invitee@beautica.test", salonId, Role.SALON_MASTER,
                FIXED_NOW, FIXED_NOW.minusSeconds(7200));

        when(inviteTokenRepository.findByIdForUpdate(inviteId)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> salonService.cancelInvite(actorId, salonId, inviteId))
                .isInstanceOf(NotFoundException.class);
        assertThat(token.getRevokedAt()).isNull();
    }

    /**
     * Double-cancel. A CANCELLED row already trips {@code isUsed()}, but the new
     * {@code revokedAt != null} rung means even a hypothetical future revocation that does NOT set
     * {@code isUsed} cannot have its timestamp overwritten by a second cancel — which matters
     * because {@code markCancelled} would otherwise move {@code revoked_at} forward and misreport
     * WHEN the invite was revoked.
     */
    @Test
    @DisplayName("cancelInvite returns 404 on a second cancel and does not move the recorded "
            + "revokedAt forward")
    void should_return404_when_cancellingAnAlreadyCancelledInvite() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        Instant firstCancelAt = FIXED_NOW.minusSeconds(900);
        InviteToken token = buildToken(inviteId, "invitee@beautica.test", salonId, Role.SALON_MASTER,
                FIXED_NOW.plusSeconds(3600), FIXED_NOW.minusSeconds(7200));
        token.markCancelled(firstCancelAt);

        when(inviteTokenRepository.findByIdForUpdate(inviteId)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> salonService.cancelInvite(actorId, salonId, inviteId))
                .isInstanceOf(NotFoundException.class);
        assertThat(token.getRevokedAt())
                .as("the FIRST cancellation is the one that happened; a later attempt must not "
                        + "restamp it with the current clock")
                .isEqualTo(firstCancelAt);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void stubHistory(UUID salonId, InviteHistoryRow... rows) {
        stubHistory(salonId, List.of(rows));
    }

    private void stubHistory(UUID salonId, List<InviteHistoryRow> rows) {
        when(inviteTokenRepository.findSalonInviteHistory(eq(salonId), any(Pageable.class)))
                .thenReturn(rows);
    }

    /** Newest-first, exactly as the repository's {@code ORDER BY created_at DESC, id DESC} returns. */
    private List<InviteHistoryRow> rows(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> row("invite-" + i + "@beautica.test", Role.SALON_MASTER, false, null,
                        FIXED_NOW.plusSeconds(3600), FIXED_NOW.minusSeconds(i)))
                .toList();
    }

    private InviteHistoryRow row(String email, Role role, boolean used, RevocationReason reason,
            Instant expiresAt, Instant createdAt) {
        return new InviteHistoryRow(UUID.randomUUID(), email, role, used, reason, expiresAt, createdAt);
    }

    private InviteToken buildToken(UUID id, String email, UUID salonId, Role role, Instant expiresAt,
            Instant createdAt) {
        InviteToken token = new InviteToken("raw-token-" + id, email, salonId, role, expiresAt);
        ReflectionTestUtils.setField(token, "id", id);
        ReflectionTestUtils.setField(token, "createdAt", createdAt);
        return token;
    }
}

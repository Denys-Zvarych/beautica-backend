package com.beautica.review.service;

import com.beautica.auth.Role;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.service.ModerationService;
import com.beautica.review.config.ClientRatingProperties;
import com.beautica.review.entity.ClientReview;
import com.beautica.review.entity.ModerationStatus;
import com.beautica.review.event.ClientReviewDeletedEvent;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B570 (REV-45, REV-D12) — {@link ClientReviewService#deleteOwn}: the REV-44 {@code own} arms per role (through the
 * real {@link ClientReviewOwnership}), the one 404 for not-found / not-own / hidden, and the delete side effects
 * (reports closed, aggregate event only for a registered subject).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClientReviewService — deleteOwn (B570)")
class ClientReviewServiceDeleteTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID SALON_ID = UUID.randomUUID();
    private static final String COMMENT = "Запізнилась";

    @Mock private ClientReviewRepository clientReviewRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private AuthorizationService authz;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ModerationService moderationService;

    private ClientReviewService service;

    @BeforeEach
    void setUp() {
        service = new ClientReviewService(clientReviewRepository, bookingRepository, authz, eventPublisher,
                new ClientRatingEligibility(new ClientRatingProperties(true)),
                new ClientReviewOwnership(authz), moderationService);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private static User user(UUID id) {
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(id);
        return user;
    }

    private static Salon salon() {
        return salon(true);
    }

    private static Salon salon(boolean active) {
        Salon salon = mock(Salon.class);
        lenient().when(salon.getId()).thenReturn(SALON_ID);
        lenient().when(salon.isActive()).thenReturn(active);
        return salon;
    }

    /** The rating's master: salon-typed and in {@code salon} when one is given, else independent. */
    private static Master master(UUID userId, Salon salon, boolean active) {
        User user = userId == null ? null : user(userId);
        Master master = mock(Master.class);
        lenient().when(master.getUser()).thenReturn(user);
        lenient().when(master.getSalon()).thenReturn(salon);
        lenient().when(master.isActive()).thenReturn(active);
        lenient().when(master.getMasterType())
                .thenReturn(salon == null ? MasterType.INDEPENDENT_MASTER : MasterType.SALON_MASTER);
        return master;
    }

    private static ClientReview review(Salon salon, UUID authorUserId, User subject) {
        return review(salon, master(authorUserId, salon, true), subject);
    }

    private static ClientReview review(Salon salon, Master author, User subject) {
        ClientReview review = ClientReview.builder()
                .salon(salon)
                .authorMaster(author)
                .subjectClient(subject)
                .subjectGuestPhone(subject == null ? "+380671234567" : null)
                .rating((short) 2)
                .comment(COMMENT)
                .build();
        ReflectionTestUtils.setField(review, "id", REVIEW_ID);
        return review;
    }

    private void stubLoad(ClientReview review) {
        when(clientReviewRepository.findByIdForUpdate(REVIEW_ID)).thenReturn(Optional.of(review));
    }

    private void assertDeleted(ClientReview review) {
        verify(clientReviewRepository).delete(review);
        verify(moderationService).closeReportsOnDeletedTarget(ReportTargetType.CLIENT_REVIEW, REVIEW_ID);
    }

    private void assertNotFoundAndUntouched(Role role) {
        assertThatThrownBy(() -> service.deleteOwn(REVIEW_ID, ACTOR, role))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(ClientReviewService.REVIEW_NOT_FOUND_MESSAGE);
        verify(clientReviewRepository, never()).delete(any(ClientReview.class));
        verifyNoInteractions(moderationService, eventPublisher);
    }

    // ── salon arm (owner / admin) ───────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SALON_OWNER", "SALON_ADMIN"})
    @DisplayName("owner / admin delete a salon rating written by ANOTHER member when they manage its salon (REV-D12)")
    void should_delete_when_salonManagerDeletesColleaguesRating(Role role) {
        ClientReview review = review(salon(), UUID.randomUUID(), user(UUID.randomUUID()));
        stubLoad(review);
        when(authz.hasManagementAccess(SALON_ID, ACTOR)).thenReturn(true);

        service.deleteOwn(REVIEW_ID, ACTOR, role);

        assertDeleted(review);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SALON_OWNER", "SALON_ADMIN"})
    @DisplayName("404 for owner / admin when the rating's salon is not one they manage")
    void should_throwNotFound_when_salonManagerOfAnotherSalon(Role role) {
        stubLoad(review(salon(), UUID.randomUUID(), user(UUID.randomUUID())));
        when(authz.hasManagementAccess(SALON_ID, ACTOR)).thenReturn(false);

        assertNotFoundAndUntouched(role);
    }

    @Test
    @DisplayName("404 for an owner on an independent (salon-less) rating — no management check is even made")
    void should_throwNotFound_when_ownerDeletesIndependentRating() {
        stubLoad(review(null, ACTOR, user(UUID.randomUUID())));

        assertNotFoundAndUntouched(Role.SALON_OWNER);
        verify(authz, never()).hasManagementAccess(any(), any());
    }

    // ── independent master arm ──────────────────────────────────────────────────

    @Test
    @DisplayName("an independent master deletes their own salon-less rating")
    void should_delete_when_independentMasterDeletesOwnRating() {
        ClientReview review = review(null, ACTOR, user(UUID.randomUUID()));
        stubLoad(review);

        service.deleteOwn(REVIEW_ID, ACTOR, Role.INDEPENDENT_MASTER);

        assertDeleted(review);
    }

    @Test
    @DisplayName("404 for an independent master on another master's rating")
    void should_throwNotFound_when_independentMasterDeletesOthersRating() {
        stubLoad(review(null, UUID.randomUUID(), user(UUID.randomUUID())));

        assertNotFoundAndUntouched(Role.INDEPENDENT_MASTER);
    }

    @Test
    @DisplayName("404 for an independent master on their own rating that carries a salon (the salon owns it)")
    void should_throwNotFound_when_independentMasterRatingHasSalon() {
        Salon salon = salon();
        Master independent = master(ACTOR, salon, true);
        lenient().when(independent.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        stubLoad(review(salon, independent, user(UUID.randomUUID())));

        assertNotFoundAndUntouched(Role.INDEPENDENT_MASTER);
    }

    // ── salon master arm ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a salon master deletes the rating on a booking they performed, whoever wrote it (REV-D12)")
    void should_delete_when_salonMasterPerformedTheBooking() {
        ClientReview review = review(salon(), ACTOR, user(UUID.randomUUID()));
        stubLoad(review);

        service.deleteOwn(REVIEW_ID, ACTOR, Role.SALON_MASTER);

        assertDeleted(review);
        verify(authz, never()).hasManagementAccess(any(), any());
    }

    @Test
    @DisplayName("404 for the performing salon master once their master row is deactivated (rating's gate)")
    void should_throwNotFound_when_performingSalonMasterDeactivated() {
        Salon salon = salon();
        stubLoad(review(salon, master(ACTOR, salon, false), user(UUID.randomUUID())));

        assertNotFoundAndUntouched(Role.SALON_MASTER);
    }

    @Test
    @DisplayName("404 for the performing salon master when the master's salon is deactivated")
    void should_throwNotFound_when_performingSalonMastersSalonInactive() {
        Salon salon = salon(false);
        stubLoad(review(salon, master(ACTOR, salon, true), user(UUID.randomUUID())));

        assertNotFoundAndUntouched(Role.SALON_MASTER);
    }

    @Test
    @DisplayName("404 for a SALON_MASTER-role caller on their own salon-less (independent-era) rating")
    void should_throwNotFound_when_salonMasterRatingHasNoSalon() {
        stubLoad(review(null, ACTOR, user(UUID.randomUUID())));

        assertNotFoundAndUntouched(Role.SALON_MASTER);
    }

    @Test
    @DisplayName("404 for a colleague salon master who did not perform the booking")
    void should_throwNotFound_when_colleagueSalonMaster() {
        stubLoad(review(salon(), UUID.randomUUID(), user(UUID.randomUUID())));

        assertNotFoundAndUntouched(Role.SALON_MASTER);
    }

    @Test
    @DisplayName("404 for a salon master when the rating's master is detached (no account)")
    void should_throwNotFound_when_authorMasterDetached() {
        stubLoad(review(salon(), (UUID) null, user(UUID.randomUUID())));

        assertNotFoundAndUntouched(Role.SALON_MASTER);
    }

    // ── other roles, visibility, missing ────────────────────────────────────────

    @Test
    @DisplayName("404 for a CLIENT even on a rating they could otherwise match")
    void should_throwNotFound_when_clientRole() {
        stubLoad(review(null, ACTOR, user(UUID.randomUUID())));

        assertNotFoundAndUntouched(Role.CLIENT);
    }

    @ParameterizedTest
    @EnumSource(value = ModerationStatus.class, names = {"HIDDEN_PENDING", "REMOVED"})
    @DisplayName("404 for a moderation-hidden rating, even the caller's own (REV-44 lists only visible)")
    void should_throwNotFound_when_reviewNotVisible(ModerationStatus status) {
        ClientReview review = review(null, ACTOR, user(UUID.randomUUID()));
        ReflectionTestUtils.setField(review, "moderationStatus", status);
        stubLoad(review);

        assertNotFoundAndUntouched(Role.INDEPENDENT_MASTER);
    }

    @Test
    @DisplayName("404 when the rating does not exist (unknown id or already deleted)")
    void should_throwNotFound_when_reviewMissing() {
        when(clientReviewRepository.findByIdForUpdate(REVIEW_ID)).thenReturn(Optional.empty());

        assertNotFoundAndUntouched(Role.SALON_OWNER);
    }

    // ── side effects ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("registered subject: delete, flush, close reports, then publish the event with the client id")
    void should_publishDeletedEventWithClientId_when_subjectRegistered() {
        UUID clientId = UUID.randomUUID();
        ClientReview review = review(null, ACTOR, user(clientId));
        stubLoad(review);
        InOrder order = inOrder(clientReviewRepository, moderationService, eventPublisher);

        service.deleteOwn(REVIEW_ID, ACTOR, Role.INDEPENDENT_MASTER);

        order.verify(clientReviewRepository).delete(review);
        order.verify(clientReviewRepository).flush();
        order.verify(moderationService).closeReportsOnDeletedTarget(ReportTargetType.CLIENT_REVIEW, REVIEW_ID);
        order.verify(eventPublisher).publishEvent(new ClientReviewDeletedEvent(clientId));
    }

    @Test
    @DisplayName("guest subject: the event carries a null client id (the listener then makes no recalculation)")
    void should_publishDeletedEventWithNullClient_when_subjectGuest() {
        ClientReview review = review(null, ACTOR, null);
        stubLoad(review);

        service.deleteOwn(REVIEW_ID, ACTOR, Role.INDEPENDENT_MASTER);

        verify(eventPublisher).publishEvent(new ClientReviewDeletedEvent(null));
    }

    // ── audit log ───────────────────────────────────────────────────────────────

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            ((Logger) LoggerFactory.getLogger(ClientReviewService.class)).detachAppender(appender);
        }
    }

    @Test
    @DisplayName("a successful delete logs one INFO audit line: role, 8-char id prefix, reports closed — no comment, "
            + "no client id, no phone, no full review id")
    void should_logAuditLineWithoutPii_when_deleted() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(ClientReviewService.class)).addAppender(appender);
        UUID clientId = UUID.randomUUID();
        ClientReview review = review(null, ACTOR, user(clientId));
        stubLoad(review);
        when(moderationService.closeReportsOnDeletedTarget(ReportTargetType.CLIENT_REVIEW, REVIEW_ID)).thenReturn(2);

        service.deleteOwn(REVIEW_ID, ACTOR, Role.INDEPENDENT_MASTER);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        String line = event.getFormattedMessage();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(line).contains(REVIEW_ID.toString().substring(0, 8), "INDEPENDENT_MASTER", "2 open reports");
        assertThat(line).doesNotContain(COMMENT, clientId.toString(), "+380", REVIEW_ID.toString(), ACTOR.toString());
    }
}

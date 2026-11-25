package com.beautica.service.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.notification.EmailService;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.service.RequestKind;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.ServiceTypeSuggestion;
import com.beautica.service.entity.ServiceTypeSuggestionStatus;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceTypeSuggestionRepository;
import com.beautica.service.service.ServiceTypeSuggestionService.DecisionOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ServiceTypeSuggestionService}. Structurally a copy of
 * {@code CategoryRequestServiceTest} — same fixed-clock, mocked-collaborator harness.
 *
 * <p>B545: decisions run only through the by-id portal path; the operator email carries the portal link, no token.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceTypeSuggestionService — unit")
class ServiceTypeSuggestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-05T10:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000545");
    private static final String PORTAL_URL = "https://beautica.app/admin/requests";
    private static final String ADMIN_EMAIL = "admin@beautica.test";
    private static final String CATEGORY = "HAIR";
    private static final String SUGGESTED = "Балаяж";
    private static final String DESCRIPTION = "Освітлення кінчиків";

    @Mock private ServiceTypeSuggestionRepository suggestionRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private EmailService emailService;
    @Mock private ServiceTypePromotionService promotionService;
    @Mock private InAppNotificationService inAppNotificationService;
    @Mock private NotificationOutboxService notificationOutboxService;

    private ServiceTypeSuggestionService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ServiceTypeSuggestionService(
                suggestionRepository, platformCategoryRepository, emailService,
                new AdminRequestsPortalLink("https://beautica.app"), promotionService, fixedClock,
                inAppNotificationService, notificationOutboxService);
        ReflectionTestUtils.setField(service, "adminEmail", ADMIN_EMAIL);
        // B505: submitSuggestion takes FOR SHARE on its category first; the default is a selectable one.
        lenient().when(platformCategoryRepository.findByNameForShare(CATEGORY))
                .thenReturn(Optional.of(PlatformCategory.ofApproved(CATEGORY, "Волосся")));
    }

    private ServiceTypeSuggestion pendingSuggestion(OffsetDateTime expiresAt) {
        return ServiceTypeSuggestion.ofPending(
                CATEGORY, SUGGESTED, DESCRIPTION, UUID.randomUUID());
    }

    // ── submitSuggestion ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a new suggestion is saved as PENDING and the admin is e-mailed the portal link")
    void should_persistPendingAndEmailAdminPortalLink_when_suggestionIsNew() {
        UUID requester = UUID.randomUUID();
        when(suggestionRepository.existsPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(false);
        when(suggestionRepository.saveAndFlush(any(ServiceTypeSuggestion.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, requester);

        ArgumentCaptor<ServiceTypeSuggestion> captor = ArgumentCaptor.forClass(ServiceTypeSuggestion.class);
        verify(suggestionRepository).saveAndFlush(captor.capture());
        ServiceTypeSuggestion saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.PENDING);
        assertThat(saved.getCategoryName()).isEqualTo(CATEGORY);
        assertThat(saved.getSuggestedName()).isEqualTo(SUGGESTED);
        assertThat(saved.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(saved.getRequestedByUserId()).isEqualTo(requester);

        // B545: the operator email carries only the portal link, never a token.
        verify(emailService).sendServiceTypeSuggestionNotification(
                eq(ADMIN_EMAIL), eq(requester.toString()), eq(CATEGORY), eq(SUGGESTED), eq(DESCRIPTION),
                eq(PORTAL_URL));
    }

    @Test
    @DisplayName("B545 — inside a transaction the operator email waits for commit; a rollback sends none")
    void should_sendNoEmail_when_transactionRollsBackAfterInsert() {
        when(suggestionRepository.existsPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(false);
        when(suggestionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, UUID.randomUUID());
            verifyNoInteractions(emailService);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("B545 — inside a transaction the operator email is sent once the transaction commits")
    void should_sendEmailAfterCommit_when_transactionCommits() {
        UUID requester = UUID.randomUUID();
        when(suggestionRepository.existsPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(false);
        when(suggestionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, requester);

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(emailService).sendServiceTypeSuggestionNotification(
                ADMIN_EMAIL, requester.toString(), CATEGORY, SUGGESTED, DESCRIPTION, PORTAL_URL);
    }

    @Test
    @DisplayName("should_emailEmDashRequester_when_requesterIdNull")
    void should_emailEmDashRequester_when_requesterIdNull() {
        when(suggestionRepository.existsPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(false);
        when(suggestionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, null);

        verify(emailService).sendServiceTypeSuggestionNotification(
                eq(ADMIN_EMAIL), eq("—"), eq(CATEGORY), eq(SUGGESTED), eq(DESCRIPTION), anyString());
    }

    @Test
    @DisplayName("should_throwConflict_when_identicalPendingSuggestionExists")
    void should_throwConflict_when_identicalPendingSuggestionExists() {
        when(suggestionRepository.existsPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("status").hasToString("409 CONFLICT");

        verify(suggestionRepository, never()).saveAndFlush(any());
        verify(suggestionRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("should_throwSameConflict_when_insertLosesRaceOnPendingDedupIndex")
    void should_throwSameConflict_when_insertLosesRaceOnPendingDedupIndex() {
        when(suggestionRepository.existsPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(false);
        when(suggestionRepository.saveAndFlush(any()))
                .thenThrow(integrityViolation(ServiceTypeSuggestionService.PENDING_DEDUP_INDEX));

        assertThatThrownBy(() -> service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("A matching suggestion is already pending review")
                .extracting("status").hasToString("409 CONFLICT");

        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("should_rethrowUnchanged_when_insertViolatesAnotherConstraint")
    void should_rethrowUnchanged_when_insertViolatesAnotherConstraint() {
        when(suggestionRepository.existsPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(false);
        DataIntegrityViolationException other = integrityViolation("fk_service_type_suggestion_requested_by");
        when(suggestionRepository.saveAndFlush(any())).thenThrow(other);

        assertThatThrownBy(() -> service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, UUID.randomUUID()))
                .isSameAs(other);

        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("B505: a category deleted (or gone) by the time the FOR SHARE lock is granted is a 400; nothing is "
            + "persisted or emailed")
    void should_throwBadRequest_when_categoryGoneUnderShareLock() {
        when(platformCategoryRepository.findByNameForShare(CATEGORY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("status").hasToString("400 BAD_REQUEST");

        verifyNoInteractions(suggestionRepository, emailService);
    }

    @Test
    @DisplayName("B505: a category hidden under the share lock is the same 400")
    void should_throwBadRequest_when_categoryHiddenUnderShareLock() {
        PlatformCategory hidden = PlatformCategory.ofApproved(CATEGORY, "Волосся");
        hidden.changeActive(false);
        when(platformCategoryRepository.findByNameForShare(CATEGORY)).thenReturn(Optional.of(hidden));

        assertThatThrownBy(() -> service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("status").hasToString("400 BAD_REQUEST");

        verifyNoInteractions(suggestionRepository, emailService);
    }

    @Test
    @DisplayName("B505: a share-lock timeout behind a portal write on the category is the typed, retry-able 409 "
            + "CATEGORY_BUSY; nothing persisted or emailed")
    void should_throwCategoryBusy_when_shareLockTimesOut() {
        when(platformCategoryRepository.findByNameForShare(CATEGORY))
                .thenThrow(new CannotAcquireLockException("lock_timeout"));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, UUID.randomUUID()));

        assertThat(thrown).isInstanceOf(CatalogRequestConflictException.class);
        assertThat(((CatalogRequestConflictException) thrown).getCode())
                .isEqualTo(CatalogRequestConflictException.Code.CATEGORY_BUSY);
        verifyNoInteractions(suggestionRepository, emailService);
    }

    @Test
    @DisplayName("B505: a committed identical suggestion is the typed DUPLICATE_PENDING; exception message and wire "
            + "message unchanged")
    void should_throwTypedDuplicatePending_when_identicalPendingSuggestionExists() {
        when(suggestionRepository.existsPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(true);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, UUID.randomUUID()));

        assertThat(thrown).isInstanceOf(CatalogRequestConflictException.class)
                .hasMessage("A matching suggestion is already pending review");
        CatalogRequestConflictException typed = (CatalogRequestConflictException) thrown;
        assertThat(typed.getCode()).isEqualTo(CatalogRequestConflictException.Code.DUPLICATE_PENDING);
        assertThat(typed.getWireMessage()).isEqualTo("Request could not be completed due to a conflict");
    }

    @Test
    @DisplayName("B505: the V197 unique-index wait timing out at the insert is the same CATEGORY_BUSY; no email")
    void should_throwCategoryBusy_when_insertLockTimesOut() {
        when(suggestionRepository.existsPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(false);
        when(suggestionRepository.saveAndFlush(any())).thenThrow(new CannotAcquireLockException("lock_timeout"));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.submitSuggestion(CATEGORY, SUGGESTED, DESCRIPTION, UUID.randomUUID()));

        assertThat(((CatalogRequestConflictException) thrown).getCode())
                .isEqualTo(CatalogRequestConflictException.Code.CATEGORY_BUSY);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("B505: the internal (category-approval) submit takes no share lock — it would wait on its own outer "
            + "approval transaction")
    void should_notLockCategory_when_submittedInternally() {
        when(suggestionRepository.existsPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, SUGGESTED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.submitSuggestionInternal(CATEGORY, SUGGESTED, null, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(platformCategoryRepository);
    }

    private static DataIntegrityViolationException integrityViolation(String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("duplicate", new SQLException("23505"), constraintName));
    }

    // ── approveById / rejectById (B545: the only decision path) ─────────────────

    @Test
    @DisplayName("should_approveAndSetDecidedAt_when_pending")
    void should_approveAndSetDecidedAt_when_pending() {
        ServiceTypeSuggestion pending = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(suggestionRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));

        DecisionOutcome outcome = service.approveById(ID);

        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        assertThat(pending.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.APPROVED);
        assertThat(pending.getDecidedAt())
                .as("decided_at stamped from the fixed clock on approval")
                .isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("should_invokePromote_when_suggestionApproved")
    void should_invokePromote_when_suggestionApproved() {
        // Phase 16.9 (supersedes 16.8): approve marks the record APPROVED AND materializes
        // the requested service via the promotion writer, in the same transaction. Verify
        // promote() is called exactly once with the just-approved suggestion.
        ServiceTypeSuggestion pending = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(suggestionRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));

        service.approveById(ID);

        verify(promotionService).promote(pending);
        // No save() on approve — the decision mutates the managed entity in place.
        verify(suggestionRepository, never()).saveAndFlush(any());
        verify(suggestionRepository, never()).save(any());
        assertThat(pending.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.APPROVED);
    }

    @Test
    @DisplayName("should_notInvokePromote_when_reject")
    void should_notInvokePromote_when_reject() {
        ServiceTypeSuggestion pending = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(suggestionRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));

        service.rejectById(ID, null);

        verifyNoInteractions(promotionService);
    }

    @Test
    @DisplayName("should_reject_when_pending")
    void should_reject_when_pending() {
        ServiceTypeSuggestion pending = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(suggestionRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));

        DecisionOutcome outcome = service.rejectById(ID, null);

        assertThat(outcome).isEqualTo(DecisionOutcome.REJECTED);
        assertThat(pending.getStatus()).isEqualTo(ServiceTypeSuggestionStatus.REJECTED);
        assertThat(pending.getDecidedAt()).isNotNull();
    }

    // ── phase 372: request-decision notification ──────────────────────────────

    @Test
    @DisplayName("approve on a PENDING suggestion promotes, THEN notifies exactly once (approved)")
    void should_notifyOnceApprovedAfterPromote_when_pendingApproved() {
        ServiceTypeSuggestion pending = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(suggestionRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));

        service.approveById(ID);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(promotionService, inAppNotificationService);
        order.verify(promotionService).promote(pending);
        order.verify(inAppNotificationService).notifySuggestionDecided(pending, true);
        org.mockito.Mockito.verifyNoMoreInteractions(inAppNotificationService);
    }

    @Test
    @DisplayName("a promotion failure propagates and no notification is written")
    void should_notNotifyAndPropagate_when_promoteThrows() {
        ServiceTypeSuggestion pending = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(suggestionRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(promotionService).promote(pending);

        assertThatThrownBy(() -> service.approveById(ID)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(inAppNotificationService);
    }

    @Test
    @DisplayName("reject with a reason on a PENDING suggestion stores the reason and notifies exactly once (rejected)")
    void should_notifyOnceRejectedAndStoreReason_when_pendingRejectedWithReason() {
        ServiceTypeSuggestion pending = pendingSuggestion(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(suggestionRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));

        service.rejectById(ID, "не підходить");

        assertThat(pending.getDecisionReason()).isEqualTo("не підходить");
        verify(inAppNotificationService).notifySuggestionDecided(pending, false);
    }

    // ── phase 491: request-decision e-mail (outbox) ───────────────────────────

    private ServiceTypeSuggestion pendingWithRequester(UUID requester) {
        ServiceTypeSuggestion pending = ServiceTypeSuggestion.ofPending(CATEGORY, SUGGESTED, DESCRIPTION, requester);
        ReflectionTestUtils.setField(pending, "id", UUID.randomUUID());
        when(suggestionRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));
        return pending;
    }

    @Test
    @DisplayName("approve enqueues exactly one REQUEST_DECIDED (approved) for the requester, after the feed item")
    void should_enqueueOneApprovedDecisionEmail_when_pendingApproved() {
        UUID requester = UUID.randomUUID();
        ServiceTypeSuggestion pending = pendingWithRequester(requester);

        service.approveById(ID);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(inAppNotificationService, notificationOutboxService);
        order.verify(inAppNotificationService).notifySuggestionDecided(pending, true);
        order.verify(notificationOutboxService).enqueueRequestDecided(
                RequestKind.SERVICE_TYPE_SUGGESTION, pending.getId().toString(), true, requester);
        verifyNoMoreInteractions(notificationOutboxService);
    }

    @Test
    @DisplayName("reject enqueues exactly one REQUEST_DECIDED (rejected) for the requester")
    void should_enqueueOneRejectedDecisionEmail_when_pendingRejected() {
        UUID requester = UUID.randomUUID();
        ServiceTypeSuggestion pending = pendingWithRequester(requester);

        service.rejectById(ID, "не підходить");

        verify(notificationOutboxService).enqueueRequestDecided(
                RequestKind.SERVICE_TYPE_SUGGESTION, pending.getId().toString(), false, requester);
        verifyNoMoreInteractions(notificationOutboxService);
    }

    @Test
    @DisplayName("a suggestion without a requester passes a null recipient (the outbox skips it)")
    void should_passNullRecipient_when_requesterIsNull() {
        ServiceTypeSuggestion pending = pendingWithRequester(null);

        service.rejectById(ID, "r");

        verify(notificationOutboxService).enqueueRequestDecided(
                RequestKind.SERVICE_TYPE_SUGGESTION, pending.getId().toString(), false, null);
    }

    @Test
    @DisplayName("a promotion failure on approve never enqueues the decision e-mail")
    void should_notEnqueueDecisionEmail_when_promotionThrows() {
        ServiceTypeSuggestion pending = pendingWithRequester(UUID.randomUUID());
        doThrow(new IllegalStateException("boom")).when(promotionService).promote(pending);

        assertThatThrownBy(() -> service.approveById(ID)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(notificationOutboxService);
    }

    // ── editPendingName (B502) ─────────────────────────────────────────────────

    private ServiceTypeSuggestion lockedPending(UUID id) {
        ServiceTypeSuggestion suggestion = pendingSuggestion(OffsetDateTime.now(Clock.fixed(NOW, ZoneOffset.UTC)).plusDays(3));
        when(suggestionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(suggestion));
        return suggestion;
    }

    @Test
    @DisplayName("should_renameAndReturnChange_when_suggestionPendingAndNameFree")
    void should_renameAndReturnChange_when_suggestionPendingAndNameFree() {
        UUID id = UUID.randomUUID();
        ServiceTypeSuggestion suggestion = lockedPending(id);
        when(suggestionRepository.existsOtherPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, "Балаяж плюс", id))
                .thenReturn(false);
        when(promotionService.activeTypeExists(CATEGORY, "Балаяж плюс")).thenReturn(false);

        ServiceTypeSuggestionService.NameChange change = service.editPendingName(id, "  Балаяж   плюс ");

        assertThat(change).isEqualTo(new ServiceTypeSuggestionService.NameChange(CATEGORY, SUGGESTED, "Балаяж плюс"));
        assertThat(suggestion.getSuggestedName()).as("canonical form stored").isEqualTo("Балаяж плюс");
        verify(suggestionRepository).saveAndFlush(suggestion);
        verifyNoInteractions(emailService, inAppNotificationService, notificationOutboxService);
    }

    @Test
    @DisplayName("should_throwNotFound_when_suggestionMissing")
    void should_throwNotFound_when_suggestionMissing() {
        UUID id = UUID.randomUUID();
        when(suggestionRepository.findByIdForUpdate(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.editPendingName(id, "Балаяж"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should_throwRequestNotPending_when_suggestionAlreadyDecided")
    void should_throwRequestNotPending_when_suggestionAlreadyDecided() {
        UUID id = UUID.randomUUID();
        ServiceTypeSuggestion suggestion = lockedPending(id);
        suggestion.approve(OffsetDateTime.now(Clock.fixed(NOW, ZoneOffset.UTC)));

        assertThatThrownBy(() -> service.editPendingName(id, "Балаяж плюс"))
                .isInstanceOfSatisfying(CatalogRequestConflictException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(CatalogRequestConflictException.Code.REQUEST_NOT_PENDING));
        verify(suggestionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("should_throw400_when_nameBreaksAllowList")
    void should_throw400_when_nameBreaksAllowList() {
        UUID id = UUID.randomUUID();
        lockedPending(id);

        assertThatThrownBy(() -> service.editPendingName(id, "Балаяж \uD83D\uDC85"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
        verify(suggestionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("should_throwDuplicatePending_when_otherPendingHasName")
    void should_throwDuplicatePending_when_otherPendingHasName() {
        UUID id = UUID.randomUUID();
        lockedPending(id);
        when(suggestionRepository.existsOtherPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, "Омбре", id))
                .thenReturn(true);

        assertThatThrownBy(() -> service.editPendingName(id, "Омбре"))
                .isInstanceOfSatisfying(CatalogRequestConflictException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(CatalogRequestConflictException.Code.DUPLICATE_PENDING));
        verify(suggestionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("should_throwDuplicatePending_when_dedupIndexRejectsFlush")
    void should_throwDuplicatePending_when_dedupIndexRejectsFlush() {
        UUID id = UUID.randomUUID();
        lockedPending(id);
        when(suggestionRepository.existsOtherPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, "Омбре", id))
                .thenReturn(false);
        when(promotionService.activeTypeExists(CATEGORY, "Омбре")).thenReturn(false);
        DataIntegrityViolationException race = new DataIntegrityViolationException("dup",
                new ConstraintViolationException("dup", new SQLException("dup"),
                        ServiceTypeSuggestionService.PENDING_DEDUP_INDEX));
        when(suggestionRepository.saveAndFlush(any(ServiceTypeSuggestion.class))).thenThrow(race);

        assertThatThrownBy(() -> service.editPendingName(id, "Омбре"))
                .isInstanceOfSatisfying(CatalogRequestConflictException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(CatalogRequestConflictException.Code.DUPLICATE_PENDING));
    }

    @Test
    @DisplayName("should_throwTypeExists_when_activeServiceTypeHasName")
    void should_throwTypeExists_when_activeServiceTypeHasName() {
        UUID id = UUID.randomUUID();
        lockedPending(id);
        when(suggestionRepository.existsOtherPendingByCategoryNameAndSuggestedNameIgnoreCase(CATEGORY, "Стрижка", id))
                .thenReturn(false);
        when(promotionService.activeTypeExists(CATEGORY, "Стрижка")).thenReturn(true);

        assertThatThrownBy(() -> service.editPendingName(id, "Стрижка"))
                .isInstanceOfSatisfying(CatalogRequestConflictException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(CatalogRequestConflictException.Code.TYPE_EXISTS));
        verify(suggestionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("should_throw400_when_canonicalNameExceeds100AfterNfkc")
    void should_throw400_when_canonicalNameExceeds100AfterNfkc() {
        UUID id = UUID.randomUUID();
        lockedPending(id);
        String ligatures = "\uFB01".repeat(100); // 100 raw chars, NFKC → 200 ("fi" × 100)

        assertThatThrownBy(() -> service.editPendingName(id, ligatures))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
        verify(suggestionRepository, never()).saveAndFlush(any());
    }
}

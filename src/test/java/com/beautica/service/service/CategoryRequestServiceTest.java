package com.beautica.service.service;

import static org.mockito.Mockito.verifyNoMoreInteractions;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.notification.EmailService;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.service.RequestKind;
import com.beautica.service.dto.CategoryRequestResponse;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.PlatformCategoryStatus;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.service.CategoryRequestService.DecisionOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryRequestService — unit")
class CategoryRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final Long ID = 41L;
    private static final String PORTAL_URL = "https://beautica.app/admin/requests";
    private static final String ADMIN_EMAIL = "admin@beautica.test";

    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private EmailService emailService;
    @Mock private ServiceTypeSuggestionService serviceTypeSuggestionService;
    @Mock private InAppNotificationService inAppNotificationService;
    @Mock private NotificationOutboxService notificationOutboxService;

    private CategoryRequestService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new CategoryRequestService(
                platformCategoryRepository, emailService, new AdminRequestsPortalLink("https://beautica.app"),
                serviceTypeSuggestionService, fixedClock, inAppNotificationService, notificationOutboxService);
        ReflectionTestUtils.setField(service, "adminEmail", ADMIN_EMAIL);
    }

    private PlatformCategory pendingCategory(OffsetDateTime expiresAt) {
        return PlatformCategory.ofPendingRequest(
                "NAIL_ART", "Нейл-арт", UUID.randomUUID(),
                OffsetDateTime.now(Clock.fixed(NOW, ZoneOffset.UTC)), null);
    }

    private PlatformCategory pendingCategory(OffsetDateTime expiresAt, UUID requester, String initialServiceName) {
        return PlatformCategory.ofPendingRequest(
                "NAIL_ART", "Нейл-арт", requester,
                OffsetDateTime.now(Clock.fixed(NOW, ZoneOffset.UTC)), initialServiceName);
    }

    private void stubSubmitHappyPath() {
        when(platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any()))
                .thenReturn(false);
        when(platformCategoryRepository.save(any(PlatformCategory.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ── submitRequest ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a new category name is saved as PENDING and the admin is e-mailed")
    void should_persistPendingAndEmailAdmin_when_nameIsNew() {
        UUID requester = UUID.randomUUID();
        when(platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any()))
                .thenReturn(false);
        when(platformCategoryRepository.save(any(PlatformCategory.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CategoryRequestResponse response = service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", null), requester);

        ArgumentCaptor<PlatformCategory> captor = ArgumentCaptor.forClass(PlatformCategory.class);
        verify(platformCategoryRepository).save(captor.capture());
        PlatformCategory saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(PlatformCategoryStatus.PENDING);
        assertThat(saved.isActive()).isFalse();
        assertThat(response.status()).isEqualTo("PENDING");
        verify(emailService).sendCategoryRequestNotification(
                eq(ADMIN_EMAIL), eq(requester.toString()), eq("NAIL_ART"), eq("Нейл-арт"),
                eq(PORTAL_URL));
    }

    @Test
    @DisplayName("B545 — inside a transaction the operator email waits for commit; a rollback sends none")
    void should_sendNoEmail_when_transactionRollsBackAfterInsert() {
        stubSubmitHappyPath();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.submitRequest(new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", null), UUID.randomUUID());
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
        stubSubmitHappyPath();
        UUID requester = UUID.randomUUID();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.submitRequest(new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", null), requester);

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(emailService).sendCategoryRequestNotification(
                ADMIN_EMAIL, requester.toString(), "NAIL_ART", "Нейл-арт", PORTAL_URL);
    }

    @Test
    @DisplayName("should_normalizeNameToUpperCase_when_submitted")
    void should_normalizeNameToUpperCase_when_submitted() {
        when(platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any()))
                .thenReturn(false);
        when(platformCategoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.submitRequest(new CreateCategoryRequestRequest("nail_art", "Нейл-арт", null), UUID.randomUUID());

        verify(platformCategoryRepository).existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any());
    }

    @Test
    @DisplayName("should_throwConflict_when_nameAlreadyExistsOrPending")
    void should_throwConflict_when_nameAlreadyExistsOrPending() {
        when(platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", null), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("status").hasToString("409 CONFLICT");

        verify(platformCategoryRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("should_throwIdenticalConflict_when_insertLosesRaceOnNameUniqueConstraint")
    void should_throwIdenticalConflict_when_insertLosesRaceOnNameUniqueConstraint() {
        CreateCategoryRequestRequest request = new CreateCategoryRequestRequest("nail_art", "Нейл-арт", null);
        when(platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any()))
                .thenReturn(true, false);
        when(platformCategoryRepository.save(any()))
                .thenThrow(integrityViolation(CategoryRequestService.NAME_UNIQUE_CONSTRAINT));
        BusinessException fromCheck = catchBusiness(request);

        BusinessException fromRace = catchBusiness(request);

        assertThat(fromRace.getStatus()).isEqualTo(HttpStatus.CONFLICT).isEqualTo(fromCheck.getStatus());
        assertThat(fromRace.getMessage())
                .isEqualTo("Category already exists or is pending: NAIL_ART")
                .isEqualTo(fromCheck.getMessage());
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("should_rethrowUnchanged_when_insertViolatesAnotherConstraint")
    void should_rethrowUnchanged_when_insertViolatesAnotherConstraint() {
        when(platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any()))
                .thenReturn(false);
        DataIntegrityViolationException other = integrityViolation("fk_platform_categories_requested_by");
        when(platformCategoryRepository.save(any())).thenThrow(other);

        assertThatThrownBy(() -> service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", null), UUID.randomUUID()))
                .isSameAs(other);

        verifyNoInteractions(emailService);
    }

    private BusinessException catchBusiness(CreateCategoryRequestRequest request) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.submitRequest(request, UUID.randomUUID()));
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return (BusinessException) thrown;
    }

    private static DataIntegrityViolationException integrityViolation(String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("duplicate", new SQLException("23505"), constraintName));
    }

    @Test
    @DisplayName("should_queryOnlyApprovedAndPendingStatuses_when_checkingForConflict")
    void should_queryOnlyApprovedAndPendingStatuses_when_checkingForConflict() {
        // The blocking set passed to the derived query must be exactly {APPROVED, PENDING}
        // — REJECTED is deliberately excluded so a previously rejected name can be resubmitted.
        when(platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", null), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlatformCategoryStatus>> statusesCaptor = ArgumentCaptor.forClass(List.class);
        verify(platformCategoryRepository)
                .existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), statusesCaptor.capture());
        assertThat(statusesCaptor.getValue())
                .as("conflict guard must block APPROVED and PENDING but NOT REJECTED")
                .containsExactlyInAnyOrder(PlatformCategoryStatus.APPROVED, PlatformCategoryStatus.PENDING)
                .doesNotContain(PlatformCategoryStatus.REJECTED);
    }

    @Test
    @DisplayName("should_persist_when_previouslyRejectedNameResubmitted")
    void should_persist_when_previouslyRejectedNameResubmitted() {
        // A name whose only prior row is REJECTED is NOT in the {APPROVED, PENDING} set,
        // so the derived query returns false and resubmission proceeds to save.
        when(platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(eq("NAIL_ART"), any()))
                .thenReturn(false);
        when(platformCategoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", null), UUID.randomUUID());

        assertThat(response.status()).isEqualTo("PENDING");
        verify(platformCategoryRepository).save(any(PlatformCategory.class));
    }

    // ── submitRequest: initialServiceName persistence (behavior #2) ─────────────

    @Test
    @DisplayName("should_persistTrimmedInitialServiceName_when_provided")
    void should_persistTrimmedInitialServiceName_when_provided() {
        stubSubmitHappyPath();

        service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", "  Класичний манікюр  "),
                UUID.randomUUID());

        ArgumentCaptor<PlatformCategory> captor = ArgumentCaptor.forClass(PlatformCategory.class);
        verify(platformCategoryRepository).save(captor.capture());
        assertThat(captor.getValue().getInitialServiceName())
                .as("a provided initial service name must persist, trimmed")
                .isEqualTo("Класичний манікюр");
    }

    @Test
    @DisplayName("should_persistNullInitialServiceName_when_absent")
    void should_persistNullInitialServiceName_when_absent() {
        stubSubmitHappyPath();

        service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", null), UUID.randomUUID());

        ArgumentCaptor<PlatformCategory> captor = ArgumentCaptor.forClass(PlatformCategory.class);
        verify(platformCategoryRepository).save(captor.capture());
        assertThat(captor.getValue().getInitialServiceName())
                .as("an absent initial service name must leave the column null")
                .isNull();
    }

    @Test
    @DisplayName("should_normalizeBlankInitialServiceNameToNull_when_blank")
    void should_normalizeBlankInitialServiceNameToNull_when_blank() {
        stubSubmitHappyPath();

        service.submitRequest(
                new CreateCategoryRequestRequest("NAIL_ART", "Нейл-арт", "   "), UUID.randomUUID());

        ArgumentCaptor<PlatformCategory> captor = ArgumentCaptor.forClass(PlatformCategory.class);
        verify(platformCategoryRepository).save(captor.capture());
        assertThat(captor.getValue().getInitialServiceName())
                .as("a blank initial service name must be normalized to null (trimToNull)")
                .isNull();
    }

    // ── approveById / rejectById (B545: the only decision path) ─────────────────

    @Test
    @DisplayName("should_approve_when_pending")
    void should_approve_when_pending() {
        PlatformCategory pending = pendingCategory(OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));

        DecisionOutcome outcome = service.approveById(ID);

        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        assertThat(pending.getStatus()).isEqualTo(PlatformCategoryStatus.APPROVED);
        assertThat(pending.isActive()).isTrue();
        assertThat(pending.getDecidedAt()).isNotNull();
    }

    // ── approve: initial-service suggestion side effect (behaviors #3, #4) ──────

    @Test
    @DisplayName("should_createPendingSuggestion_when_approvedCategoryCarriesInitialServiceName")
    void should_createPendingSuggestion_when_approvedCategoryCarriesInitialServiceName() {
        UUID requester = UUID.randomUUID();
        PlatformCategory pending = pendingCategory(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC), requester, "Класичний манікюр");
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));

        DecisionOutcome outcome = service.approveById(ID);

        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        // suggestion submitted for the approved slug (category.getName()), requester carried, description null
        verify(serviceTypeSuggestionService).submitSuggestionInternal(
                eq("NAIL_ART"), eq("Класичний манікюр"), isNull(), eq(requester));
    }

    @Test
    @DisplayName("should_notCreateSuggestion_when_approvedCategoryHasNoInitialServiceName")
    void should_notCreateSuggestion_when_approvedCategoryHasNoInitialServiceName() {
        PlatformCategory pending = pendingCategory(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC), UUID.randomUUID(), null);
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));

        DecisionOutcome outcome = service.approveById(ID);

        assertThat(outcome).isEqualTo(DecisionOutcome.APPROVED);
        verifyNoInteractions(serviceTypeSuggestionService);
    }

    @Test
    @DisplayName("should_keepApprovalCommitted_when_suggestionThrows409Conflict")
    void should_keepApprovalCommitted_when_suggestionThrows409Conflict() {
        // The REQUIRES_NEW boundary isolates the inner suggestion rollback; a 409 dedup
        // must be swallowed so the category approval still stands.
        UUID requester = UUID.randomUUID();
        PlatformCategory pending = pendingCategory(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC), requester, "Дубль");
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));
        doThrow(new BusinessException(HttpStatus.CONFLICT, "A matching suggestion is already pending review"))
                .when(serviceTypeSuggestionService)
                .submitSuggestionInternal(eq("NAIL_ART"), eq("Дубль"), isNull(), eq(requester));

        DecisionOutcome outcome = service.approveById(ID);

        assertThat(outcome)
                .as("a swallowed 409 must not propagate; approval outcome stands")
                .isEqualTo(DecisionOutcome.APPROVED);
        assertThat(pending.getStatus())
                .as("category must end APPROVED despite the inner suggestion conflict")
                .isEqualTo(PlatformCategoryStatus.APPROVED);
        assertThat(pending.isActive()).isTrue();
    }

    @Test
    @DisplayName("should_propagate_when_suggestionThrowsNon409BusinessException")
    void should_propagate_when_suggestionThrowsNon409BusinessException() {
        // Only CONFLICT is swallowed; any other BusinessException must bubble up so a
        // genuine fault is not silently lost behind the approval.
        UUID requester = UUID.randomUUID();
        PlatformCategory pending = pendingCategory(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC), requester, "Помилка");
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));
        doThrow(new BusinessException(HttpStatus.BAD_REQUEST, "boom"))
                .when(serviceTypeSuggestionService)
                .submitSuggestionInternal(eq("NAIL_ART"), eq("Помилка"), isNull(), eq(requester));

        assertThatThrownBy(() -> service.approveById(ID))
                .isInstanceOf(BusinessException.class)
                .extracting("status").hasToString("400 BAD_REQUEST");
    }

    @Test
    @DisplayName("should_reject_when_pending")
    void should_reject_when_pending() {
        PlatformCategory pending = pendingCategory(OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));

        DecisionOutcome outcome = service.rejectById(ID, null);

        assertThat(outcome).isEqualTo(DecisionOutcome.REJECTED);
        assertThat(pending.getStatus()).isEqualTo(PlatformCategoryStatus.REJECTED);
        assertThat(pending.isActive()).isFalse();
    }

    // ── listApproved ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_mapApprovedCategories_when_listApproved")
    void should_mapApprovedCategories_when_listApproved() {
        when(platformCategoryRepository.findApprovedActive())
                .thenReturn(List.of(PlatformCategory.ofApproved("MANICURE", "Манікюр")));

        var result = service.listApproved();

        assertThat(result).singleElement()
                .satisfies(c -> {
                    assertThat(c.name()).isEqualTo("MANICURE");
                    assertThat(c.displayName()).isEqualTo("Манікюр");
                });
    }

    // ── phase 372: request-decision notification ──────────────────────────────

    @Test
    @DisplayName("approve on a PENDING request notifies the requester exactly once (approved)")
    void should_notifyOnceApproved_when_pendingApproved() {
        PlatformCategory pending = pendingCategory(OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));

        service.approveById(ID);

        verify(inAppNotificationService).notifyCategoryRequestDecided(pending, true);
        verifyNoMoreInteractions(inAppNotificationService);
    }

    @Test
    @DisplayName("reject with a reason on a PENDING request stores the reason and notifies exactly once (rejected)")
    void should_notifyOnceRejectedAndStoreReason_when_pendingRejectedWithReason() {
        PlatformCategory pending = pendingCategory(OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));

        service.rejectById(ID, "дублікат");

        assertThat(pending.getDecisionReason()).isEqualTo("дублікат");
        verify(inAppNotificationService).notifyCategoryRequestDecided(pending, false);
        verifyNoMoreInteractions(inAppNotificationService);
    }

    @Test
    @DisplayName("reject without a reason stores no reason")
    void should_storeNoReason_when_rejectedWithoutReason() {
        PlatformCategory pending = pendingCategory(OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));

        service.rejectById(ID, null);

        assertThat(pending.getDecisionReason()).isNull();
        verify(inAppNotificationService).notifyCategoryRequestDecided(pending, false);
    }

    // ── phase 491: request-decision e-mail (outbox) ───────────────────────────

    private PlatformCategory pendingWithId(UUID requester, long id) {
        PlatformCategory pending = pendingCategory(
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC), requester, null);
        ReflectionTestUtils.setField(pending, "id", id);
        when(platformCategoryRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(pending));
        return pending;
    }

    @Test
    @DisplayName("approve enqueues exactly one REQUEST_DECIDED (approved) for the requester")
    void should_enqueueOneApprovedDecisionEmail_when_pendingApproved() {
        UUID requester = UUID.randomUUID();
        pendingWithId(requester, 42L);

        service.approveById(ID);

        verify(notificationOutboxService).enqueueRequestDecided(RequestKind.CATEGORY_REQUEST, "42", true, requester);
        verifyNoMoreInteractions(notificationOutboxService);
    }

    @Test
    @DisplayName("reject enqueues exactly one REQUEST_DECIDED (rejected) for the requester")
    void should_enqueueOneRejectedDecisionEmail_when_pendingRejected() {
        UUID requester = UUID.randomUUID();
        pendingWithId(requester, 43L);

        service.rejectById(ID, "дублікат");

        verify(notificationOutboxService).enqueueRequestDecided(RequestKind.CATEGORY_REQUEST, "43", false, requester);
        verifyNoMoreInteractions(notificationOutboxService);
    }

    @Test
    @DisplayName("a request without a requester passes a null recipient (the outbox skips it)")
    void should_passNullRecipient_when_requesterIsNull() {
        pendingWithId(null, 44L);

        service.rejectById(ID, "r");

        verify(notificationOutboxService).enqueueRequestDecided(RequestKind.CATEGORY_REQUEST, "44", false, null);
    }

    // ── editPendingName (B502) ─────────────────────────────────────────────────

    private PlatformCategory lockedPending(long id, String initialServiceName) {
        PlatformCategory category = pendingCategory(
                OffsetDateTime.now(Clock.fixed(NOW, ZoneOffset.UTC)).plusDays(3), UUID.randomUUID(), initialServiceName);
        when(platformCategoryRepository.findByIdForUpdate(id)).thenReturn(Optional.of(category));
        return category;
    }

    @Test
    @DisplayName("should_editDisplayNameAndKeepWireNameAndInitial_when_pendingAndInitialAbsent")
    void should_editDisplayNameAndKeepWireNameAndInitial_when_pendingAndInitialAbsent() {
        PlatformCategory category = lockedPending(7L, "Френч");

        CategoryRequestService.NameChange change = service.editPendingName(7L, " Нейл  арт ", null);

        assertThat(change).isEqualTo(new CategoryRequestService.NameChange("Нейл-арт", "Нейл арт", "Френч", "Френч"));
        assertThat(category.getDisplayName()).isEqualTo("Нейл арт");
        assertThat(category.getName()).as("wire name immutable (D4)").isEqualTo("NAIL_ART");
        assertThat(category.getInitialServiceName()).isEqualTo("Френч");
        verify(platformCategoryRepository).saveAndFlush(category);
        verifyNoInteractions(emailService, inAppNotificationService, notificationOutboxService);
    }

    @Test
    @DisplayName("should_replaceOrClearInitialServiceName_when_given")
    void should_replaceOrClearInitialServiceName_when_given() {
        PlatformCategory replaced = lockedPending(8L, "Френч");
        PlatformCategory cleared = lockedPending(9L, "Френч");

        service.editPendingName(8L, "Нейл-арт", "Френч  манікюр");
        service.editPendingName(9L, "Нейл-арт", "   ");

        assertThat(replaced.getInitialServiceName()).isEqualTo("Френч манікюр");
        assertThat(cleared.getInitialServiceName()).as("blank clears").isNull();
    }

    @Test
    @DisplayName("should_throwNotFound_when_categoryMissing")
    void should_throwNotFound_when_categoryMissing() {
        when(platformCategoryRepository.findByIdForUpdate(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.editPendingName(5L, "Нейл-арт", null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should_throwRequestNotPending_when_categoryAlreadyApproved")
    void should_throwRequestNotPending_when_categoryAlreadyApproved() {
        PlatformCategory category = lockedPending(7L, null);
        category.approve(OffsetDateTime.now(Clock.fixed(NOW, ZoneOffset.UTC)));

        assertThatThrownBy(() -> service.editPendingName(7L, "Нейл арт", null))
                .isInstanceOfSatisfying(CatalogRequestConflictException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(CatalogRequestConflictException.Code.REQUEST_NOT_PENDING));
        assertThat(category.getDisplayName()).isEqualTo("Нейл-арт");
    }

    @Test
    @DisplayName("should_throw400_when_displayNameBreaksAllowListOrIsBlank")
    void should_throw400_when_displayNameBreaksAllowListOrIsBlank() {
        PlatformCategory category = lockedPending(7L, null);

        assertThatThrownBy(() -> service.editPendingName(7L, "Нейл \u2764", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.editPendingName(7L, "\u00A0 ", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(category.getDisplayName()).isEqualTo("Нейл-арт");
    }
}

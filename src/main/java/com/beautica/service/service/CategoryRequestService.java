package com.beautica.service.service;

import com.beautica.catalog.icon.CategoryIconGate;
import com.beautica.catalog.icon.CategoryIconUrls;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.notification.EmailService;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.service.RequestKind;
import com.beautica.service.dto.ApprovedCategoryResponse;
import com.beautica.service.dto.CategoryRequestResponse;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.PlatformCategoryStatus;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.user.AfterCommitHandOff;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Self-service service-category request workflow.
 *
 * <p>One table ({@code platform_categories}) backs both the catalog and the request record — no parallel table. A
 * request is a PENDING, inactive row; the platform operator gets a notification-only email linking to the catalog
 * portal and decides it there ({@link #approveById} / {@link #rejectById}, B503), at which point it becomes
 * {@code APPROVED + active} and selectable platform-wide.
 *
 * <p>B545 (SVC-61): email approve / reject is retired. No token is minted or stored; the former emailed links answer
 * 410 ({@code RetiredCatalogRequestLinksController}) and never reach this service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryRequestService {

    /** V64 {@code UNIQUE(name)} — blocks a second row with the same name in ANY status. */
    static final String NAME_UNIQUE_CONSTRAINT = "uq_platform_categories_name";
    static final String DUPLICATE_CATEGORY_MESSAGE_PREFIX = "Category already exists or is pending: ";
    /** Named cache for the global APPROVED+active category list. */
    static final String APPROVED_CATEGORIES_CACHE = "approved-categories";
    /** Constant key — the approved list is platform-wide, not per-user. */
    static final String APPROVED_CATEGORIES_KEY = "'all'";
    private static final List<PlatformCategoryStatus> BLOCKING_STATUSES =
            List.of(PlatformCategoryStatus.APPROVED, PlatformCategoryStatus.PENDING);

    private final PlatformCategoryRepository platformCategoryRepository;
    private final EmailService emailService;
    private final AdminRequestsPortalLink adminRequestsPortalLink;
    private final ServiceTypeSuggestionService serviceTypeSuggestionService;
    private final Clock clock;
    private final InAppNotificationService inAppNotificationService;
    private final NotificationOutboxService notificationOutboxService;
    private final CategoryIconUrls categoryIconUrls;
    private final CategoryIconGate categoryIconGate;

    @Value("${app.admin-email}")
    private String adminEmail;

    /** Outcome of a portal decision. */
    public enum DecisionOutcome {
        APPROVED,
        REJECTED
    }

    /**
     * Submits a new category request: inserts a PENDING row and emails the operator a notification-only message that
     * links to the portal's requests page (B545; direct send, NTF-D12), handed off only after the transaction commits.
     * Returns a minimal DTO.
     *
     * @throws BusinessException 409 if the (normalized) name already exists as
     *                           APPROVED or PENDING
     */
    @Transactional
    public CategoryRequestResponse submitRequest(CreateCategoryRequestRequest request, UUID requesterId) {
        String name = request.name().toUpperCase(Locale.ROOT);

        if (platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(name, BLOCKING_STATUSES)) {
            throw duplicateCategory(name);
        }

        PlatformCategory category = PlatformCategory.ofPendingRequest(
                name, request.displayName(), requesterId, OffsetDateTime.now(clock),
                trimToNull(request.initialServiceName()));
        PlatformCategory saved = insertPending(category);

        String requester = requesterId != null ? requesterId.toString() : "—";
        String portalUrl = adminRequestsPortalLink.requestsUrl();
        // B545 — after commit only: a rolled-back insert (e.g. a lost UNIQUE race) never announces a request.
        AfterCommitHandOff.submit(Runnable::run,
                () -> emailService.sendCategoryRequestNotification(
                        adminEmail, requester, saved.getName(), saved.getDisplayName(), portalUrl),
                log, "Category request operator email");

        return CategoryRequestResponse.from(saved);
    }

    /** B502 — the editable fields before and after a portal edit (for the caller's audit row). */
    public record NameChange(String displayNameBefore, String displayNameAfter,
                             String initialServiceNameBefore, String initialServiceNameAfter) {}

    /**
     * B502 (SVC-55) — the platform operator corrects the requested display name (and optionally the carried initial
     * service name) while the request is PENDING. The wire {@code name} never changes (track D4). The display name
     * follows the service-name rules (SVC-07 allow-list, SVC-08 100 chars, {@code ServiceCatalogService.canonicalName});
     * the initial service name goes through the suggestion validator it eventually feeds ({@code
     * canonicalSuggestedName}). {@code initialServiceName}: {@code null} keeps the current value, a blank string clears
     * it. Pending rows are not cached, so nothing is evicted.
     *
     * @throws NotFoundException               no category row with this id
     * @throws CatalogRequestConflictException {@code REQUEST_NOT_PENDING} when the row is not a PENDING request
     */
    @Transactional
    public NameChange editPendingName(Long categoryId, String rawDisplayName, String rawInitialServiceName) {
        PlatformCategory category = platformCategoryRepository.findByIdForUpdate(categoryId)
                .orElseThrow(() -> new NotFoundException("Request not found"));
        if (!category.isPending()) {
            throw new CatalogRequestConflictException(CatalogRequestConflictException.Code.REQUEST_NOT_PENDING);
        }
        String displayName = ServiceCatalogService.canonicalName(rawDisplayName);
        if (displayName == null || displayName.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Name is required");
        }
        String initialBefore = category.getInitialServiceName();
        String initialAfter = resolveInitialServiceName(rawInitialServiceName, initialBefore);
        String displayBefore = category.getDisplayName();
        category.editPendingRequest(displayName, initialAfter);
        flushEdit(category);
        return new NameChange(displayBefore, displayName, initialBefore, initialAfter);
    }

    /**
     * Flushes now (same as the suggestion edit) so a constraint violation surfaces inside the service: a
     * {@value #NAME_UNIQUE_CONSTRAINT} hit maps to the request 409 instead of a 500 at commit. The wire name is never
     * changed here, so that branch is defensive.
     */
    private void flushEdit(PlatformCategory category) {
        try {
            platformCategoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException ex) {
            if (isNameUniqueViolation(ex)) {
                throw new CatalogRequestConflictException(CatalogRequestConflictException.Code.DUPLICATE_PENDING);
            }
            throw ex;
        }
    }

    private static String resolveInitialServiceName(String raw, String current) {
        if (raw == null) {
            return current;
        }
        return raw.isBlank() ? null : ServiceCatalogService.canonicalSuggestedName(raw);
    }

    /**
     * Approved + active categories for the authenticated mobile picker.
     *
     * <p>Cached under a single global {@code 'all'} key — the list is platform-wide.
     * Eviction: {@code CatalogCacheEvictor.evictPlatformCatalog()} after a portal approve commits (B503 D5; since B545 the only
     * approve eviction), {@code CatalogCacheEvictor.evictPlatformCatalog()} after an icon PUT commits (B539, {@code iconUrl}) and the
     * {@code @CacheEvict} on {@link InternalCategoryService#create}; reject changes no cached data. An in-flight load that started before the commit is bounded only by the 60-minute TTL, acceptable
     * for a low-frequency admin click.
     * {@code submitRequest} only inserts PENDING rows (not in this list) so it needs
     * no eviction.
     *
     * <p>The sibling {@link PlatformCategoryOrderLookup#CACHE_NAME} cache is evicted alongside this one by the same
     * paths: it backs {@code ServiceCatalogService#buildCategoryOrderAndNames} with the same
     * {@code findApprovedActive()} query result, kept as a separate cache/bean because it returns entities for
     * internal ordering rather than the {@link ApprovedCategoryResponse} DTO this method exposes.
     *
     * <p>{@code sync = true} (B503): one hot platform-wide key, so a TTL expiry or an approve's eviction loads it once
     * instead of once per concurrent picker.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = APPROVED_CATEGORIES_CACHE, key = APPROVED_CATEGORIES_KEY, sync = true)
    public List<ApprovedCategoryResponse> listApproved() {
        return platformCategoryRepository.findApprovedActive().stream()
                .map(category -> ApprovedCategoryResponse.from(category, categoryIconUrls::urlFor))
                .toList();
    }

    /**
     * B503 (SVC-06) — the platform operator approves the category request from the catalog portal; since B545 the
     * only decision path. It runs {@link #decide(PlatformCategory, boolean, String)} (APPROVED + active, the carried
     * initial-service suggestion, in-app item, decision e-mail). The row is locked ({@code FOR UPDATE}, the lock B502's
     * edit takes), so the first decision wins.
     *
     * <p>No cache annotation here: the caller ({@code CatalogRequestAdminService}) runs this inside its own
     * transaction and calls {@code CatalogCacheEvictor.evictPlatformCatalog()} after commit; a nested {@code @CacheEvict} would
     * fire before that outer commit (Anti-Bug §F-2).
     *
     * @throws NotFoundException               no category row with this id
     * @throws CatalogRequestConflictException {@code REQUEST_NOT_PENDING} when the row is not a PENDING request
     */
    @Transactional
    public DecisionOutcome approveById(Long categoryId) {
        return decide(lockPendingById(categoryId), true, null);
    }

    /**
     * B503 — portal reject; see {@link #approveById}. {@code reason} is already cleaned and length-checked by the
     * caller ({@code DecisionReason}); {@code null} when none.
     */
    @Transactional
    public DecisionOutcome rejectById(Long categoryId, String reason) {
        return decide(lockPendingById(categoryId), false, reason);
    }

    private PlatformCategory lockPendingById(Long categoryId) {
        PlatformCategory category = platformCategoryRepository.findByIdForUpdate(categoryId)
                .orElseThrow(() -> new NotFoundException("Request not found"));
        if (!category.isPending()) {
            throw new CatalogRequestConflictException(CatalogRequestConflictException.Code.REQUEST_NOT_PENDING);
        }
        return category;
    }

    /**
     * The ONE decision for a locked PENDING request ({@link #approveById} / {@link #rejectById}) — B503 D1. B541
     * (SVC-60): an approve of a category without an icon is refused ({@code CATEGORY_ICON_REQUIRED}, while
     * {@code app.catalog.icon-required} is on) before anything is written; reject never needs an icon.
     */
    private DecisionOutcome decide(PlatformCategory category, boolean approve, String reason) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (approve) {
            categoryIconGate.requireIcon(category);
            category.approve(now);
            createInitialServiceSuggestion(category);
            // Same transaction as the decision (MANDATORY): the feed item commits or rolls back with it.
            // Only reached on the PENDING transition — lockPendingById rejects any other status first.
            inAppNotificationService.notifyCategoryRequestDecided(category, true);
            enqueueDecisionEmail(category, true);
            return DecisionOutcome.APPROVED;
        }
        category.reject(now, reason);
        inAppNotificationService.notifyCategoryRequestDecided(category, false);
        enqueueDecisionEmail(category, false);
        return DecisionOutcome.REJECTED;
    }

    /**
     * Phase 491 — the requester's decision e-mail (NTF-35), one outbox row in the decision transaction
     * (MANDATORY); skipped by the outbox when there is no requester. Ids only: the name and the reason
     * are read at drain time.
     */
    private void enqueueDecisionEmail(PlatformCategory category, boolean approved) {
        notificationOutboxService.enqueueRequestDecided(RequestKind.CATEGORY_REQUEST,
                String.valueOf(category.getId()), approved, category.getRequestedByUserId());
    }

    /**
     * If the approved request carried an initial service-type name, turn it into a
     * PENDING {@link com.beautica.service.entity.ServiceTypeSuggestion} for the
     * now-approved category (promotion now happens when the resulting suggestion is
     * itself approved; Phase 16.9). A 409 dedup conflict is swallowed: the suggestion
     * runs in its own REQUIRES_NEW transaction so its rollback cannot roll back the
     * category approval (B545 D4: keep REQUIRES_NEW).
     */
    private void createInitialServiceSuggestion(PlatformCategory category) {
        String initialServiceName = category.getInitialServiceName();
        if (initialServiceName == null) {
            return;
        }
        try {
            serviceTypeSuggestionService.submitSuggestionInternal(
                    category.getName(), initialServiceName, null, category.getRequestedByUserId());
        } catch (BusinessException e) {
            if (e.getStatus() != HttpStatus.CONFLICT) {
                throw e;
            }
            // 409 dedup: a matching suggestion already pending. No-op — the category
            // approval must stand regardless. The inner REQUIRES_NEW transaction has
            // already rolled back in isolation, so the approval commit is unaffected.
        }
    }

    /**
     * Inserts the PENDING row. The exists-check above is check-then-act; two concurrent submits can
     * both pass it, and {@value #NAME_UNIQUE_CONSTRAINT} (V64) is what actually serialises them. The
     * id is {@code IDENTITY}, so {@code save} INSERTs immediately — a lost race surfaces HERE, before
     * the admin email, with no {@code saveAndFlush} needed. The loser is translated to the SAME 409
     * the exists-check throws; any other integrity violation is rethrown unchanged (generic handler,
     * anti-enumeration).
     */
    private PlatformCategory insertPending(PlatformCategory category) {
        try {
            return platformCategoryRepository.save(category);
        } catch (DataIntegrityViolationException ex) {
            if (isNameUniqueViolation(ex)) {
                throw duplicateCategory(category.getName());
            }
            throw ex;
        }
    }

    /**
     * Matches only Hibernate's structured {@code getConstraintName()} — never a message substring
     * (the PgJDBC detail line echoes caller-supplied text). Same rationale as
     * {@code ServiceCatalogService.isDuplicateServiceViolation}.
     */
    private static boolean isNameUniqueViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException hce
                    && NAME_UNIQUE_CONSTRAINT.equals(hce.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private static BusinessException duplicateCategory(String name) {
        return BusinessException.withUserInput(HttpStatus.CONFLICT, DUPLICATE_CATEGORY_MESSAGE_PREFIX + name);
    }

    /** Blank or null becomes {@code null}; otherwise the trimmed value. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

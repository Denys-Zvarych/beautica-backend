package com.beautica.service.service;

import com.beautica.auth.TokenGenerator;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.PublicBaseUrlProperties;
import com.beautica.notification.EmailService;
import com.beautica.service.dto.ApprovedCategoryResponse;
import com.beautica.service.dto.CategoryRequestResponse;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.PlatformCategoryStatus;
import com.beautica.service.repository.PlatformCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Self-service service-category request + email-approval workflow.
 *
 * <p>One table ({@code platform_categories}) backs both the catalog and the
 * request record — no parallel table. A request is a PENDING, inactive row carrying
 * a hashed single-use token; a platform admin approves it via an emailed link, at
 * which point it becomes {@code APPROVED + active} and selectable platform-wide.
 *
 * <p>Security properties enforced here:
 * <ul>
 *   <li>Raw token never persisted — only {@code SHA-256(token)} hex is stored.</li>
 *   <li>Token matched in constant time ({@link MessageDigest#isEqual}).</li>
 *   <li>7-day expiry; single-use (token nulled on decision).</li>
 *   <li>Decisions only mutate state on the POST paths (approve/reject), never on
 *       the GET review load — defeats email-scanner link pre-fetch.</li>
 *   <li>Invalid/expired/already-decided tokens map to a neutral outcome so the
 *       caller cannot probe request existence.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CategoryRequestService {

    private static final Duration TOKEN_TTL = Duration.ofDays(7);
    // Token is carried as the trailing PATH SEGMENT (not a query param) so it is not
    // exposed in proxy access logs or leaked via the Referer header of assets loaded
    // on the review page. The base64url token charset (A-Za-z0-9-_) is path-safe.
    private static final String REVIEW_PATH = "/api/v1/service-categories/requests/review/";
    /** Named cache for the global APPROVED+active category list. */
    static final String APPROVED_CATEGORIES_CACHE = "approved-categories";
    /** Constant key — the approved list is platform-wide, not per-user. */
    static final String APPROVED_CATEGORIES_KEY = "'all'";
    private static final List<PlatformCategoryStatus> BLOCKING_STATUSES =
            List.of(PlatformCategoryStatus.APPROVED, PlatformCategoryStatus.PENDING);

    private final PlatformCategoryRepository platformCategoryRepository;
    private final TokenGenerator tokenGenerator;
    private final EmailService emailService;
    private final PublicBaseUrlProperties publicBaseUrlProperties;
    private final ServiceTypeSuggestionService serviceTypeSuggestionService;
    private final Clock clock;

    @Value("${app.admin-email}")
    private String adminEmail;

    /**
     * Outcome of a token-bearing review/decision operation. The controller maps it
     * to a neutral HTML page; no variant leaks whether a request existed.
     */
    public enum DecisionOutcome {
        APPROVED,
        REJECTED,
        ALREADY_DECIDED,
        INVALID_OR_EXPIRED
    }

    /** Loaded request details for the read-only review page (no state change). */
    public record ReviewView(boolean valid, String categoryName, String displayName) {
        static ReviewView invalid() {
            return new ReviewView(false, null, null);
        }
    }

    /**
     * Submits a new category request: inserts a PENDING row and emails the admin a
     * single-use approval link. Returns a minimal DTO; never the token.
     *
     * @throws BusinessException 409 if the (normalized) name already exists as
     *                           APPROVED or PENDING
     */
    @Transactional
    public CategoryRequestResponse submitRequest(CreateCategoryRequestRequest request, UUID requesterId) {
        String name = request.name().toUpperCase(Locale.ROOT);

        if (platformCategoryRepository.existsByNameIgnoreCaseAndStatusIn(name, BLOCKING_STATUSES)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Category already exists or is pending: " + name);
        }

        String rawToken = tokenGenerator.generateToken();
        String tokenHash = tokenGenerator.hash(rawToken);
        OffsetDateTime now = OffsetDateTime.now(clock);

        PlatformCategory category = PlatformCategory.ofPendingRequest(
                name, request.displayName(), requesterId, tokenHash, now, now.plus(TOKEN_TTL),
                trimToNull(request.initialServiceName()));
        PlatformCategory saved = platformCategoryRepository.save(category);

        String reviewUrl = publicBaseUrlProperties.getPublicBaseUrl() + REVIEW_PATH + rawToken;
        emailService.sendCategoryRequestNotification(
                adminEmail,
                requesterId != null ? requesterId.toString() : "—",
                saved.getName(),
                saved.getDisplayName(),
                reviewUrl);

        return CategoryRequestResponse.from(saved);
    }

    /**
     * Read-only load for the review page. NO state change. Returns an invalid view
     * for any token that does not match a live PENDING request, so the page is
     * neutral and does not reveal request existence.
     */
    @Transactional(readOnly = true)
    public ReviewView loadForReview(String rawToken) {
        return findLivePending(rawToken)
                .map(c -> new ReviewView(true, c.getName(), c.getDisplayName()))
                .orElseGet(ReviewView::invalid);
    }

    /**
     * Approves the PENDING request identified by the token: APPROVED + active,
     * token cleared (single-use). Idempotent: a second call returns
     * {@link DecisionOutcome#ALREADY_DECIDED} (or INVALID_OR_EXPIRED) — no 500.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = APPROVED_CATEGORIES_CACHE, key = APPROVED_CATEGORIES_KEY),
            @CacheEvict(value = PlatformCategoryOrderLookup.CACHE_NAME, key = PlatformCategoryOrderLookup.CACHE_KEY)
    })
    public DecisionOutcome approve(String rawToken) {
        return decide(rawToken, true);
    }

    /**
     * Rejects the PENDING request identified by the token: REJECTED, token cleared.
     * Idempotent (see {@link #approve}).
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = APPROVED_CATEGORIES_CACHE, key = APPROVED_CATEGORIES_KEY),
            @CacheEvict(value = PlatformCategoryOrderLookup.CACHE_NAME, key = PlatformCategoryOrderLookup.CACHE_KEY)
    })
    public DecisionOutcome reject(String rawToken) {
        return decide(rawToken, false);
    }

    /**
     * Approved + active categories for the authenticated mobile picker.
     *
     * <p>Cached under a single global {@code 'all'} key — the list is platform-wide.
     * Eviction is the inline {@code @CacheEvict} on {@link #approve} / {@link #reject}
     * (the only paths that change APPROVED/active membership). Inline (pre-commit)
     * eviction is acceptable here rather than the afterCommit pattern because both
     * decision paths are extremely low-frequency admin clicks (one per emailed
     * approval link) with no realistic concurrent reader racing to repopulate a
     * stale snapshot between eviction and commit; the 60-minute TTL is the backstop.
     * {@code submitRequest} only inserts PENDING rows (not in this list) so it needs
     * no eviction.
     *
     * <p>The sibling {@link PlatformCategoryOrderLookup#CACHE_NAME} cache is evicted
     * alongside this one on {@link #approve}/{@link #reject}: it backs {@code
     * ServiceCatalogService#buildCategoryOrderAndNames} with the same {@code findApprovedActive()}
     * query result, kept as a separate cache/bean because it returns entities for internal
     * ordering rather than the {@link ApprovedCategoryResponse} DTO this method exposes.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = APPROVED_CATEGORIES_CACHE, key = APPROVED_CATEGORIES_KEY)
    public List<ApprovedCategoryResponse> listApproved() {
        return platformCategoryRepository.findApprovedActive().stream()
                .map(ApprovedCategoryResponse::from)
                .toList();
    }

    private DecisionOutcome decide(String rawToken, boolean approve) {
        Optional<PlatformCategory> match = findByTokenConstantTime(rawToken);
        if (match.isEmpty()) {
            // Token unknown. The row may already be decided (token nulled) — we
            // cannot distinguish, so return a neutral already-decided outcome.
            return DecisionOutcome.ALREADY_DECIDED;
        }

        PlatformCategory category = match.get();
        if (!category.isPending()) {
            return DecisionOutcome.ALREADY_DECIDED;
        }
        if (isExpired(category)) {
            return DecisionOutcome.INVALID_OR_EXPIRED;
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (approve) {
            category.approve(now);
            createInitialServiceSuggestion(category);
            return DecisionOutcome.APPROVED;
        }
        category.reject(now);
        return DecisionOutcome.REJECTED;
    }

    /**
     * If the approved request carried an initial service-type name, turn it into a
     * PENDING {@link com.beautica.service.entity.ServiceTypeSuggestion} for the
     * now-approved category (promotion now happens when the resulting suggestion is
     * itself approved; Phase 16.9). A 409 dedup conflict is swallowed: the suggestion
     * runs in its own REQUIRES_NEW transaction so its rollback cannot roll back the
     * category approval.
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

    /** Blank or null becomes {@code null}; otherwise the trimmed value. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Optional<PlatformCategory> findLivePending(String rawToken) {
        return findByTokenConstantTime(rawToken)
                .filter(PlatformCategory::isPending)
                .filter(c -> !isExpired(c));
    }

    /**
     * Looks up the request by hashed token, then re-verifies the stored hash against
     * the recomputed hash in constant time ({@link MessageDigest#isEqual}). The DB
     * lookup is an equality match; the constant-time re-check is defense-in-depth so
     * the decision branch never depends on a non-constant-time String compare.
     */
    private Optional<PlatformCategory> findByTokenConstantTime(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String candidateHash = tokenGenerator.hash(rawToken);
        return platformCategoryRepository.findByTokenHash(candidateHash)
                .filter(c -> c.getTokenHash() != null
                        && MessageDigest.isEqual(
                                c.getTokenHash().getBytes(StandardCharsets.UTF_8),
                                candidateHash.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean isExpired(PlatformCategory category) {
        OffsetDateTime expiresAt = category.getTokenExpiresAt();
        return expiresAt == null || expiresAt.isBefore(OffsetDateTime.now(clock));
    }
}

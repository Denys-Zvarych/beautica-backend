package com.beautica.service.service;

import com.beautica.auth.TokenGenerator;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.PublicBaseUrlProperties;
import com.beautica.notification.EmailService;
import com.beautica.service.entity.ServiceTypeSuggestion;
import com.beautica.service.repository.ServiceTypeSuggestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Self-service service-type suggestion + email-approval workflow.
 *
 * <p>A suggestion is persisted as a PENDING {@code service_type_suggestion} row
 * carrying a hashed single-use token; a platform admin approves or rejects it via an
 * emailed token-authenticated link. Approve marks the record {@code APPROVED} and then
 * materializes the requested service for the requesting master via
 * {@link ServiceTypePromotionService} — in the SAME transaction, so a promotion failure
 * rolls back the approval and keeps it retryable (Phase 16.9; supersedes the Phase 16.8
 * "no auto-promotion" decision).
 *
 * <p>Structurally a copy of {@link CategoryRequestService}. Security properties
 * enforced here, verbatim from that workflow:
 * <ul>
 *   <li>Raw token never persisted — only {@code SHA-256(token)} hex is stored.</li>
 *   <li>Token matched in constant time ({@link MessageDigest#isEqual}).</li>
 *   <li>7-day expiry; single-use (token nulled on decision).</li>
 *   <li>Decisions only mutate state on the POST paths (approve/reject), never on
 *       the GET review load — defeats email-scanner link pre-fetch.</li>
 *   <li>Invalid/expired/already-decided tokens map to a neutral outcome so the
 *       caller cannot probe suggestion existence.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ServiceTypeSuggestionService {

    private static final Duration TOKEN_TTL = Duration.ofDays(7);
    private static final String REVIEW_PATH = "/api/v1/service-types/suggestions/review?token=";

    private final ServiceTypeSuggestionRepository suggestionRepository;
    private final TokenGenerator tokenGenerator;
    private final EmailService emailService;
    private final PublicBaseUrlProperties publicBaseUrlProperties;
    private final ServiceTypePromotionService promotionService;
    private final Clock clock;

    @Value("${app.admin-email}")
    private String adminEmail;

    /**
     * Outcome of a token-bearing review/decision operation. The controller maps it
     * to a neutral HTML page; no variant leaks whether a suggestion existed.
     */
    public enum DecisionOutcome {
        APPROVED,
        REJECTED,
        ALREADY_DECIDED,
        INVALID_OR_EXPIRED
    }

    /** Loaded suggestion details for the read-only review page (no state change). */
    public record ReviewView(boolean valid, String categoryName, String suggestedName, String description) {
        static ReviewView invalid() {
            return new ReviewView(false, null, null, null);
        }
    }

    /**
     * Submits a new service-type suggestion: inserts a PENDING row and emails the
     * admin a single-use review link. The token is never returned.
     *
     * <p>The caller ({@code ServiceCatalogService.suggestServiceType}) has already
     * validated the category slug is active + APPROVED (Phase 16.7 guard) before
     * delegating here.
     *
     * @throws BusinessException 409 if an identical PENDING suggestion already exists
     *                           (same category slug + case-insensitive name) — matches
     *                           the category-request 409 dedup convention.
     */
    @Transactional
    public void submitSuggestion(
            String categoryName, String suggestedName, String description, UUID requestedByUserId) {
        persistPendingAndNotify(categoryName, suggestedName, description, requestedByUserId);
    }

    /**
     * Submits a PENDING suggestion from an already-trusted internal caller (e.g. the
     * category-request approval path turning a carried "initial service name" into a
     * suggestion). Bypasses the controller slug guard — the caller supplies the
     * now-APPROVED System-B category slug directly — but applies the same 409 dedup and
     * persistence as {@link #submitSuggestion}.
     *
     * <p>Marked {@code @Transactional(propagation = REQUIRES_NEW)} so the suggestion
     * insert (and its dedup check) runs in its OWN suborbital transaction. A 409
     * {@link BusinessException} rolls back only that inner transaction; the caller
     * catches it and the outer category-approval transaction commits unaffected. Using
     * the default {@code REQUIRED} here would mark the shared transaction rollback-only
     * on the throw (Spring footgun) and doom the approval even if the caller catches it.
     *
     * @throws BusinessException 409 if an identical PENDING suggestion already exists
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void submitSuggestionInternal(
            String categoryName, String suggestedName, String description, UUID requestedByUserId) {
        persistPendingAndNotify(categoryName, suggestedName, description, requestedByUserId);
    }

    private void persistPendingAndNotify(
            String categoryName, String suggestedName, String description, UUID requestedByUserId) {

        if (suggestionRepository.existsPendingByCategoryNameAndSuggestedNameIgnoreCase(
                categoryName, suggestedName)) {
            throw new BusinessException(
                    HttpStatus.CONFLICT, "A matching suggestion is already pending review");
        }

        String rawToken = tokenGenerator.generateToken();
        String tokenHash = tokenGenerator.hash(rawToken);
        OffsetDateTime now = OffsetDateTime.now(clock);

        ServiceTypeSuggestion suggestion = ServiceTypeSuggestion.ofPending(
                categoryName, suggestedName, description, requestedByUserId, tokenHash, now.plus(TOKEN_TTL));
        suggestionRepository.save(suggestion);

        String reviewUrl = publicBaseUrlProperties.getPublicBaseUrl() + REVIEW_PATH + rawToken;
        emailService.sendServiceTypeSuggestionNotification(
                adminEmail,
                requestedByUserId != null ? requestedByUserId.toString() : "—",
                categoryName,
                suggestedName,
                description,
                reviewUrl);
    }

    /**
     * Read-only load for the review page. NO state change. Returns an invalid view
     * for any token that does not match a live PENDING suggestion, so the page is
     * neutral and does not reveal suggestion existence.
     */
    @Transactional(readOnly = true)
    public ReviewView loadForReview(String rawToken) {
        return findLivePending(rawToken)
                .map(s -> new ReviewView(true, s.getCategoryName(), s.getSuggestedName(), s.getDescription()))
                .orElseGet(ReviewView::invalid);
    }

    /**
     * Approves the PENDING suggestion identified by the token: APPROVED, token cleared
     * (single-use), and the requested service materialized for the requesting master via
     * {@link ServiceTypePromotionService} in this same transaction (Phase 16.9). A
     * promotion failure rolls the whole approval back, so the admin can retry cleanly.
     * Idempotent: a second call returns {@link DecisionOutcome#ALREADY_DECIDED} (or
     * INVALID_OR_EXPIRED) BEFORE reaching the promote call — no 500, no double-create.
     */
    @Transactional
    public DecisionOutcome approve(String rawToken) {
        return decide(rawToken, true);
    }

    /**
     * Rejects the PENDING suggestion identified by the token: REJECTED, token
     * cleared. Idempotent (see {@link #approve}).
     */
    @Transactional
    public DecisionOutcome reject(String rawToken) {
        return decide(rawToken, false);
    }

    private DecisionOutcome decide(String rawToken, boolean approve) {
        Optional<ServiceTypeSuggestion> match = findByTokenConstantTime(rawToken);
        if (match.isEmpty()) {
            // Token unknown. The row may already be decided (token nulled) — we
            // cannot distinguish, so return a neutral already-decided outcome.
            return DecisionOutcome.ALREADY_DECIDED;
        }

        ServiceTypeSuggestion suggestion = match.get();
        if (!suggestion.isPending()) {
            return DecisionOutcome.ALREADY_DECIDED;
        }
        if (isExpired(suggestion)) {
            return DecisionOutcome.INVALID_OR_EXPIRED;
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (approve) {
            suggestion.approve(now);          // flips APPROVED + clears the single-use token
            // Materialize the requested service in the SAME transaction (Phase 16.9). A throw
            // here rolls back the approve() flip (status stays PENDING, token intact) so the
            // admin can retry — no "approved but not created" split-brain.
            promotionService.promote(suggestion);
            return DecisionOutcome.APPROVED;
        }
        suggestion.reject(now);
        return DecisionOutcome.REJECTED;
    }

    private Optional<ServiceTypeSuggestion> findLivePending(String rawToken) {
        return findByTokenConstantTime(rawToken)
                .filter(ServiceTypeSuggestion::isPending)
                .filter(s -> !isExpired(s));
    }

    /**
     * Looks up the suggestion by hashed token, then re-verifies the stored hash
     * against the recomputed hash in constant time ({@link MessageDigest#isEqual}).
     * The DB lookup is an equality match; the constant-time re-check is
     * defense-in-depth so the decision branch never depends on a non-constant-time
     * String compare.
     */
    private Optional<ServiceTypeSuggestion> findByTokenConstantTime(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String candidateHash = tokenGenerator.hash(rawToken);
        return suggestionRepository.findByTokenHash(candidateHash)
                .filter(s -> s.getTokenHash() != null
                        && MessageDigest.isEqual(
                                s.getTokenHash().getBytes(StandardCharsets.UTF_8),
                                candidateHash.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean isExpired(ServiceTypeSuggestion suggestion) {
        OffsetDateTime expiresAt = suggestion.getTokenExpiresAt();
        return expiresAt == null || expiresAt.isBefore(OffsetDateTime.now(clock));
    }
}

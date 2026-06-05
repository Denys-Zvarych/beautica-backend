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
 * emailed token-authenticated link. Approve marks the record {@code APPROVED} and
 * notifies — it does <strong>NOT</strong> insert a {@code service_types} row (no
 * auto-promotion; Phase 16.8 decision).
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
     * Approves the PENDING suggestion identified by the token: APPROVED, token
     * cleared (single-use). Does NOT insert a {@code service_types} row. Idempotent:
     * a second call returns {@link DecisionOutcome#ALREADY_DECIDED} (or
     * INVALID_OR_EXPIRED) — no 500.
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
            suggestion.approve(now);
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

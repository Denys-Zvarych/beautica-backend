package com.beautica.review.invite.service;

import com.beautica.common.security.PhoneHasher;
import com.beautica.common.security.PhoneHasher.HashedPhone;
import com.beautica.config.BookingSmsProperties;
import com.beautica.review.invite.config.ReviewInviteProperties;
import com.beautica.review.invite.entity.ReviewInviteSkipReason;
import com.beautica.review.invite.repository.SmsOptOutRepository;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Phase 400 D2 — the ONE eligibility predicate, applied when the invite is created and again when it is sent. Gates
 * run in a fixed order and the first failure names the skip reason:
 * <ol>
 *   <li>{@code app.booking.review-invite.enabled} → {@link ReviewInviteSkipReason#INVITES_DISABLED}</li>
 *   <li>{@code app.booking.sms.enabled} → {@link ReviewInviteSkipReason#SMS_DISABLED}</li>
 *   <li>guest PII anonymised → {@link ReviewInviteSkipReason#ANONYMISED}</li>
 *   <li>no (valid) guest phone → {@link ReviewInviteSkipReason#NO_PHONE}</li>
 *   <li>phone hash in {@code sms_opt_outs} → {@link ReviewInviteSkipReason#OPTED_OUT}</li>
 *   <li>accepted policy older than {@code min-policy-version} (or none) → {@link ReviewInviteSkipReason#POLICY_VERSION}</li>
 *   <li>no COMPLETED booking left without a review → {@link ReviewInviteSkipReason#NOTHING_TO_REVIEW}</li>
 * </ol>
 * A visit that is not a LINK guest visit never gets a row ({@link VisitSnapshot#isGuestLinkVisit}); should one ever
 * reach the send path it is reported as {@code NOTHING_TO_REVIEW} — there is nothing a guest may review there.
 * The opt-out lookup runs only when every cheaper gate before it passed. The send job preloads it for a whole batch
 * ({@link #preload}, one query, each phone hashed once) and passes the result to
 * {@link #evaluateInBatch(VisitSnapshot, PreloadedBatch)}; the create path keeps the single-row check.
 */
@Component
public class ReviewInviteEligibility {

    private final ReviewInviteProperties properties;
    private final BookingSmsProperties bookingSmsProperties;
    private final PhoneHasher phoneHasher;
    private final SmsOptOutRepository smsOptOutRepository;
    private final Optional<PolicyVersion> minPolicyVersion;

    public ReviewInviteEligibility(ReviewInviteProperties properties,
                                   BookingSmsProperties bookingSmsProperties,
                                   PhoneHasher phoneHasher,
                                   SmsOptOutRepository smsOptOutRepository) {
        this.properties = properties;
        this.bookingSmsProperties = bookingSmsProperties;
        this.phoneHasher = phoneHasher;
        this.smsOptOutRepository = smsOptOutRepository;
        this.minPolicyVersion = PolicyVersion.parse(properties.minPolicyVersion());
    }

    /** The verdict for {@code visit}; carries the phone hash whenever the phone could be hashed. */
    public Verdict evaluate(VisitSnapshot visit) {
        return evaluate(visit, smsOptOutRepository::existsByPhoneHash);
    }

    /**
     * Phase 400 audit cycle 1 — {@link #evaluate(VisitSnapshot)} with the opt-out gate answered by {@code optedOut}
     * (a phone hash → opted out?), e.g. {@link PreloadedBatch#optedOut} from {@link #preload}. Same gates, same order.
     */
    public Verdict evaluate(VisitSnapshot visit, Predicate<byte[]> optedOut) {
        return evaluate(visit, () -> phoneHasher.tryHash(visit.guestPhone()), optedOut);
    }

    /**
     * Phase 400 audit cycle 2 — {@link #evaluate(VisitSnapshot, Predicate)} for a row of a {@link #preload}ed batch:
     * the phone hash computed by the preload is reused, so each phone is HMAC'd once per send tick, not twice. A
     * snapshot the batch never saw falls back to hashing it here. Same gates, same order, same verdicts.
     */
    public Verdict evaluateInBatch(VisitSnapshot visit, PreloadedBatch batch) {
        return evaluate(visit, () -> batch.phones().containsKey(visit.guestPhone())
                ? batch.phones().get(visit.guestPhone())
                : phoneHasher.tryHash(visit.guestPhone()), batch.optedOut());
    }

    private Verdict evaluate(VisitSnapshot visit, Supplier<Optional<HashedPhone>> hashedPhone,
                             Predicate<byte[]> optedOut) {
        if (!properties.enabled()) {
            return Verdict.skip(null, ReviewInviteSkipReason.INVITES_DISABLED);
        }
        if (!bookingSmsProperties.getSms().isEnabled()) {
            return Verdict.skip(null, ReviewInviteSkipReason.SMS_DISABLED);
        }
        if (visit.anonymised()) {
            return Verdict.skip(null, ReviewInviteSkipReason.ANONYMISED);
        }
        Optional<HashedPhone> phone = hashedPhone.get();
        if (phone.isEmpty()) {
            return Verdict.skip(null, ReviewInviteSkipReason.NO_PHONE);
        }
        return evaluateWithPhone(visit, phone.get(), optedOut);
    }

    /**
     * Phase 400 audit cycles 1+2 — the opt-out gate for a whole batch in one query ({@link PreloadedBatch#optedOut}
     * answers "has this phone hash opted out?" from memory), plus each phone's hash (keyed by the raw phone, so a guest
     * with two due invites is hashed once) for {@link #evaluateInBatch}. Hashes nothing and runs no query when a flag
     * is off (every row is skipped before the gate); runs no query when no snapshot has a hashable phone.
     */
    public PreloadedBatch preload(Collection<VisitSnapshot> visits) {
        if (!properties.enabled() || !bookingSmsProperties.getSms().isEnabled()) {
            return PreloadedBatch.EMPTY;
        }
        Map<String, Optional<HashedPhone>> phones = new HashMap<>();
        for (VisitSnapshot visit : visits) {
            if (!visit.anonymised()) {
                phones.computeIfAbsent(visit.guestPhone(), phoneHasher::tryHash);
            }
        }
        List<byte[]> hashes = phones.values().stream().flatMap(Optional::stream).map(HashedPhone::hash).toList();
        if (hashes.isEmpty()) {
            return new PreloadedBatch(Collections.unmodifiableMap(phones), hash -> false);
        }
        Set<ByteBuffer> optedOut = smsOptOutRepository.findExistingHashes(hashes).stream()
                .map(ByteBuffer::wrap)
                .collect(Collectors.toUnmodifiableSet());
        return new PreloadedBatch(Collections.unmodifiableMap(phones), hash -> optedOut.contains(ByteBuffer.wrap(hash)));
    }

    /**
     * Phase 400 audit cycle 2 — one send batch's precomputed gate inputs.
     *
     * @param phones    raw guest phone → its {@code PhoneHasher} key (empty when unhashable); never logged. Holds the
     *                  raw phone only for the lifetime of one claim transaction
     * @param optedOut  the batch opt-out lookup (phone hash → opted out?)
     */
    public record PreloadedBatch(Map<String, Optional<HashedPhone>> phones, Predicate<byte[]> optedOut) {

        /**
         * Nothing preloaded: every row is hashed on demand and nobody is opted out (both flags off).
         * {@code Collections.emptyMap()}, not {@code Map.of()}: {@link #evaluateInBatch} looks up a possibly-null
         * guest phone, and {@code Map.of().containsKey(null)} throws NPE.
         */
        public static final PreloadedBatch EMPTY = new PreloadedBatch(Collections.emptyMap(), hash -> false);

        @Override
        public String toString() {
            return "PreloadedBatch[phones=<redacted " + phones.size() + ">]";
        }
    }

    private Verdict evaluateWithPhone(VisitSnapshot visit, HashedPhone phone, Predicate<byte[]> optedOut) {
        if (optedOut.test(phone.hash())) {
            return Verdict.skip(phone, ReviewInviteSkipReason.OPTED_OUT);
        }
        if (!policyAccepted(visit.policyVersion())) {
            return Verdict.skip(phone, ReviewInviteSkipReason.POLICY_VERSION);
        }
        if (!visit.isGuestLinkVisit() || !visit.hasUnreviewedCompleted()) {
            return Verdict.skip(phone, ReviewInviteSkipReason.NOTHING_TO_REVIEW);
        }
        return Verdict.eligible(phone);
    }

    private boolean policyAccepted(String accepted) {
        return minPolicyVersion.isPresent()
                && PolicyVersion.parse(accepted).map(v -> v.isAtLeast(minPolicyVersion.get())).orElse(false);
    }

    /**
     * Outcome of {@link #evaluate}.
     *
     * @param phone      the guest phone hash, {@code null} when a gate before hashing failed
     * @param skipReason {@code null} when eligible
     */
    public record Verdict(HashedPhone phone, ReviewInviteSkipReason skipReason) {

        static Verdict eligible(HashedPhone phone) {
            return new Verdict(phone, null);
        }

        static Verdict skip(HashedPhone phone, ReviewInviteSkipReason reason) {
            return new Verdict(phone, reason);
        }

        public boolean isEligible() {
            return skipReason == null;
        }
    }
}

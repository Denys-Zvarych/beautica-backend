package com.beautica.review.invite.service;

import com.beautica.common.security.PhoneHasher;
import com.beautica.common.security.PhoneHasher.HashedPhone;
import com.beautica.review.invite.config.ReviewInviteProperties;
import com.beautica.review.invite.entity.ReviewInviteSkipReason;
import com.beautica.review.invite.repository.ReviewInviteRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * Phase 434 audit-fix (sec M1/M2, LOW-1/LOW-4) — the abuse gates that apply to a STAFF walk-in invite ONLY. A STAFF booking is
 * created by the provider being rated, who also types the guest phone, so unlike a LINK guest the phone proves
 * nothing:
 * <ol>
 *   <li>{@link ReviewInviteSkipReason#PROVIDER_OWN_PHONE} — the walk-in phone is the account phone of the performing
 *       master or, for a salon booking, of anyone on the salon's staff: owner, admins, salon masters (self-review
 *       farming, M2 / LOW-1);</li>
 *   <li>{@link ReviewInviteSkipReason#REPEAT_PHONE} — the phone already got a non-skipped invite for the same provider
 *       within {@code app.booking.review-invite.staff-phone-window} (SMS spam and friend-phone farming, M1/M2);</li>
 *   <li>{@link ReviewInviteSkipReason#PHONE_GLOBAL_CAP} — the phone already has {@code staff-phone-global-cap}
 *       non-skipped STAFF invites across ALL providers within {@code staff-phone-window} (cross-provider SMS spam,
 *       LOW-4);</li>
 *   <li>{@link ReviewInviteSkipReason#PROVIDER_DAILY_CAP} — the provider (master, or salon of a salon booking) already
 *       created {@code staff-provider-daily-cap} non-skipped STAFF invites in the last {@link #PROVIDER_CAP_WINDOW}
 *       (bulk SMS through invented walk-ins, LOW-4).</li>
 * </ol>
 * Cheapest first; each later gate runs only when every earlier one passed. Every count is anchored like
 * {@link ReviewInviteRepository#existsPriorInviteForPhoneAndProvider} (only EARLIER invites count), so the send-time
 * re-check keeps the older of two rows.
 * Phones are compared by {@link PhoneHasher} key, so formatting differences in {@code users.phone_number} do not
 * matter. A LINK visit or a STAFF visit with an account is never checked here (no query runs).
 */
@Component
public class StaffWalkInInviteGuard {

    /** The rolling window of {@link ReviewInviteSkipReason#PROVIDER_DAILY_CAP}. */
    public static final Duration PROVIDER_CAP_WINDOW = Duration.ofDays(1);

    private final ReviewInviteRepository reviewInviteRepository;
    private final PhoneHasher phoneHasher;
    private final ReviewInviteProperties properties;
    private final Clock clock;

    public StaffWalkInInviteGuard(ReviewInviteRepository reviewInviteRepository,
                                  PhoneHasher phoneHasher,
                                  ReviewInviteProperties properties,
                                  Clock clock) {
        this.reviewInviteRepository = reviewInviteRepository;
        this.phoneHasher = phoneHasher;
        this.properties = properties;
        this.clock = clock;
    }

    /** The skip reason for a STAFF walk-in {@code visit} with guest phone {@code phone}, or empty when it may go out. */
    public Optional<ReviewInviteSkipReason> check(VisitSnapshot visit, HashedPhone phone) {
        if (!visit.isGuestStaffVisit()) {
            return Optional.empty();
        }
        if (isProviderOwnPhone(visit, phone)) {
            return Optional.of(ReviewInviteSkipReason.PROVIDER_OWN_PHONE);
        }
        Instant now = clock.instant();
        long windowSeconds = properties.staffPhoneWindow().toSeconds();
        if (reviewInviteRepository.existsPriorInviteForPhoneAndProvider(phone.hash(), visit.appointmentId(),
                visit.bookingId(), now, windowSeconds)) {
            return Optional.of(ReviewInviteSkipReason.REPEAT_PHONE);
        }
        if (reviewInviteRepository.countPriorStaffInvitesForPhone(phone.hash(), visit.appointmentId(),
                visit.bookingId(), now, windowSeconds) >= properties.staffPhoneGlobalCap()) {
            return Optional.of(ReviewInviteSkipReason.PHONE_GLOBAL_CAP);
        }
        if (reviewInviteRepository.countRecentStaffInvitesForProvider(visit.appointmentId(), visit.bookingId(), now,
                PROVIDER_CAP_WINDOW.toSeconds()) >= properties.staffProviderDailyCap()) {
            return Optional.of(ReviewInviteSkipReason.PROVIDER_DAILY_CAP);
        }
        return Optional.empty();
    }

    private boolean isProviderOwnPhone(VisitSnapshot visit, HashedPhone phone) {
        byte[] guest = phone.hash();
        return reviewInviteRepository.findProviderAccountPhones(visit.appointmentId(), visit.bookingId()).stream()
                .map(phoneHasher::tryHash)
                .flatMap(Optional::stream)
                .anyMatch(provider -> Arrays.equals(provider.hash(), guest));
    }
}

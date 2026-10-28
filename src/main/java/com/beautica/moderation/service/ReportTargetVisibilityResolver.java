package com.beautica.moderation.service;

import com.beautica.auth.Role;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.media.entity.EntityType;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;
import com.beautica.media.repository.MediaRepository;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.review.entity.ClientReview;
import com.beautica.review.entity.ModerationStatus;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 395 D2/D5 — decides whether a report target is reportable by the caller, and whether the caller owns it.
 *
 * <p>A target is reportable only when the reporter can read it today through an existing endpoint, mirroring
 * that endpoint's own gate (one method per {@link ReportTargetType}):
 * <ul>
 *   <li>{@code REVIEW} — {@code GET /reviews/{id}} is public: any {@code VISIBLE} review. Phase 396: a hidden
 *       ({@code HIDDEN_PENDING}) or {@code REMOVED} review is the same 404 as a missing one, exactly as
 *       {@code GET /reviews/{id}} answers — it is no longer reportable (no existence oracle, no reopened case).
 *       The same rule applies to {@code CLIENT_REVIEW}.</li>
 *   <li>{@code PORTFOLIO_PHOTO} — {@code GET /masters|salons/{id}/portfolio} is public: any {@code PORTFOLIO}
 *       media row ({@code AVATAR} rows are reported through the profile they belong to).</li>
 *   <li>{@code MASTER_PROFILE} — {@code GET /masters/{id}}: any master that is not detached.</li>
 *   <li>{@code SALON_PROFILE} — {@code GET /salons/{id}}: any active salon.</li>
 *   <li>{@code CLIENT_PROFILE} / {@code CLIENT_REVIEW} — only a provider who has a booking with that client
 *       (performing master, salon owner, or the salon's assigned admin), see
 *       {@link BookingRepository#existsClientBookingVisibleToProvider}.</li>
 * </ul>
 * Anything else resolves to {@link Optional#empty()}, which the service turns into the SAME 404 as a missing id
 * (no existence oracle).
 *
 * <p><b>Ownership (D5).</b> {@link ReportTarget#ownedByReporter()} is true for the review's author (for a
 * {@code CLIENT_REVIEW} also the owner/assigned admin of the booking's salon, who writes it under the performing
 * master's name), the photo's master or the salon's owner/assigned admin (who can edit or delete it themselves), the master's own user, and
 * the client themselves. Salon masters are not owners of their salon's profile or photos: they cannot edit
 * them, so reporting is their only remedy.
 *
 * <p>Repository access is direct (as in {@code AuthorizationService}): every lookup is one primary-key read or
 * one bounded {@code EXISTS}, and lazy associations are touched only for their id, which a Hibernate proxy
 * answers without loading the row. One exception: {@code CLIENT_REVIEW} reads
 * {@code authorMaster.user}, which initialises the author {@code Master} proxy (one extra primary-key read) —
 * the same cost as an {@code existsByIdAndUserId} probe, so it is kept.
 */
@Component
@RequiredArgsConstructor
public class ReportTargetVisibilityResolver {

    private static final Set<Role> PROVIDER_ROLES = EnumSet.of(
            Role.SALON_OWNER, Role.SALON_ADMIN, Role.SALON_MASTER, Role.INDEPENDENT_MASTER);

    private final ReviewRepository reviewRepository;
    private final ClientReviewRepository clientReviewRepository;
    private final MediaRepository mediaRepository;
    private final MasterRepository masterRepository;
    private final SalonRepository salonRepository;
    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;

    /** Whether the reporter owns the (visible) target. */
    public record ReportTarget(boolean ownedByReporter) {}

    @Transactional(readOnly = true)
    public Optional<ReportTarget> resolve(ReportTargetType type, UUID targetId, UUID reporterId, Role reporterRole) {
        return switch (type) {
            case REVIEW -> resolveReview(targetId, reporterId);
            case CLIENT_REVIEW -> resolveClientReview(targetId, reporterId, reporterRole);
            case PORTFOLIO_PHOTO -> resolvePortfolioPhoto(targetId, reporterId, reporterRole);
            case MASTER_PROFILE -> resolveMasterProfile(targetId, reporterId);
            case SALON_PROFILE -> resolveSalonProfile(targetId, reporterId, reporterRole);
            case CLIENT_PROFILE -> resolveClientProfile(targetId, reporterId, reporterRole);
        };
    }

    private Optional<ReportTarget> resolveReview(UUID reviewId, UUID reporterId) {
        return reviewRepository.findById(reviewId)
                .filter(review -> review.getModerationStatus() == ModerationStatus.VISIBLE)
                .map(review -> new ReportTarget(isUser(review.getClient(), reporterId)));
    }

    private Optional<ReportTarget> resolveClientReview(UUID clientReviewId, UUID reporterId, Role reporterRole) {
        Optional<ClientReview> found = clientReviewRepository.findById(clientReviewId)
                .filter(review -> review.getModerationStatus() == ModerationStatus.VISIBLE);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ClientReview review = found.get();
        if (authoredOrManagedByReporter(review, reporterId, reporterRole)) {
            return Optional.of(new ReportTarget(true));
        }
        User subject = review.getSubjectClient();
        return providerSeesClient(subject.getId(), reporterId, reporterRole)
                ? Optional.of(new ReportTarget(false))
                : Optional.empty();
    }

    /**
     * D5 for a {@code CLIENT_REVIEW}: the performing master who is stored as its author, OR the owner / assigned
     * admin of the booking's salon. A salon review written by the owner or admin is stored under the performing
     * master ({@code ClientReviewService#createClientReview}), so without the salon arm its real author could
     * report their own words. A salon master is never an owner here (they cannot write the salon's reviews).
     */
    private boolean authoredOrManagedByReporter(ClientReview review, UUID reporterId, Role reporterRole) {
        if (isUser(review.getAuthorMaster().getUser(), reporterId)) {
            return true;
        }
        Salon salon = review.getSalon();
        return salon != null && managesSalon(salon.getId(), reporterId, reporterRole);
    }

    private Optional<ReportTarget> resolvePortfolioPhoto(UUID mediaId, UUID reporterId, Role reporterRole) {
        return mediaRepository.findById(mediaId)
                .filter(media -> media.getMediaType() == MediaType.PORTFOLIO)
                .filter(media -> media.getEntityType() == EntityType.MASTER
                        || media.getEntityType() == EntityType.SALON)
                .map(media -> new ReportTarget(ownsPortfolioPhoto(media, reporterId, reporterRole)));
    }

    private boolean ownsPortfolioPhoto(MediaFile media, UUID reporterId, Role reporterRole) {
        if (media.getEntityType() == EntityType.MASTER) {
            return masterRepository.existsByIdAndUserId(media.getEntityId(), reporterId);
        }
        return managesSalon(media.getEntityId(), reporterId, reporterRole);
    }

    private Optional<ReportTarget> resolveMasterProfile(UUID masterId, UUID reporterId) {
        return masterRepository.findById(masterId)
                .filter(master -> !master.isDetached())
                .map(master -> new ReportTarget(ownsMaster(master, reporterId)));
    }

    private static boolean ownsMaster(Master master, UUID reporterId) {
        return isUser(master.getUser(), reporterId);
    }

    private Optional<ReportTarget> resolveSalonProfile(UUID salonId, UUID reporterId, Role reporterRole) {
        return salonRepository.findById(salonId)
                .filter(Salon::isActive)
                .map(salon -> new ReportTarget(isUser(salon.getOwner(), reporterId)
                        || isAssignedAdmin(salon.getId(), reporterId, reporterRole)));
    }

    private Optional<ReportTarget> resolveClientProfile(UUID clientUserId, UUID reporterId, Role reporterRole) {
        if (clientUserId.equals(reporterId)) {
            return Optional.of(new ReportTarget(true));
        }
        return providerSeesClient(clientUserId, reporterId, reporterRole)
                ? Optional.of(new ReportTarget(false))
                : Optional.empty();
    }

    /** D2 — a provider sees a client (profile and the reviews written about them) only through a shared booking. */
    private boolean providerSeesClient(UUID clientUserId, UUID reporterId, Role reporterRole) {
        if (!PROVIDER_ROLES.contains(reporterRole)) {
            return false;
        }
        if (reporterRole == Role.SALON_ADMIN) {
            return userRepository.findSalonIdById(reporterId)
                    .map(salonId -> bookingRepository.existsByClientIdAndSalonId(clientUserId, salonId))
                    .orElse(false);
        }
        return bookingRepository.existsClientBookingVisibleToProvider(
                clientUserId, reporterId, reporterRole == Role.SALON_MASTER);
    }

    private boolean managesSalon(UUID salonId, UUID reporterId, Role reporterRole) {
        return salonRepository.existsByIdAndOwnerId(salonId, reporterId)
                || isAssignedAdmin(salonId, reporterId, reporterRole);
    }

    private boolean isAssignedAdmin(UUID salonId, UUID reporterId, Role reporterRole) {
        return reporterRole == Role.SALON_ADMIN
                && userRepository.findSalonIdById(reporterId).map(salonId::equals).orElse(false);
    }

    private static boolean isUser(User user, UUID userId) {
        return user != null && userId.equals(user.getId());
    }
}

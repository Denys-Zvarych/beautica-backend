package com.beautica.moderation.service;

import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;
import com.beautica.media.repository.MediaRepository;
import com.beautica.moderation.dto.ModerationQueueItem;
import com.beautica.moderation.dto.ModerationTargetSnapshot;
import com.beautica.moderation.entity.ContentReport;
import com.beautica.moderation.entity.ReportStatus;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.repository.ContentReportRepository;
import com.beautica.review.entity.ClientReview;
import com.beautica.review.entity.Review;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Phase 396 D4 — the support moderation queue: one page of reports in one status, each with a snapshot of its target
 * (review text, photo URL, profile name). OPEN is oldest first by filing time (24 h SLA); RESOLVED is newest
 * RESOLUTION first ({@code resolvedAt DESC, id DESC}, D17 — reports closed together tie on resolvedAt). Snapshots are
 * loaded in ONE primary-key batch per target type present on the page (at most six statements), never per row.
 * Repository access is direct, as in {@link ReportTargetVisibilityResolver}: read-only primary-key lookups.
 */
@Service
@RequiredArgsConstructor
public class ModerationQueueService {

    private static final String CREATED_AT = "createdAt";
    private static final String RESOLVED_AT = "resolvedAt";
    private static final String ID = "id";

    private final ContentReportRepository contentReportRepository;
    private final ReviewRepository reviewRepository;
    private final ClientReviewRepository clientReviewRepository;
    private final MediaRepository mediaRepository;
    private final MasterRepository masterRepository;
    private final SalonRepository salonRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Page<ModerationQueueItem> list(ReportStatus status, Pageable pageable) {
        Sort sort = status == ReportStatus.OPEN
                ? Sort.by(CREATED_AT).ascending()
                : Sort.by(Sort.Order.desc(RESOLVED_AT), Sort.Order.desc(ID));
        Page<ContentReport> page = contentReportRepository.findByStatus(
                status, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort));
        Map<ReportTargetType, Map<UUID, ModerationTargetSnapshot>> snapshots = loadSnapshots(page.getContent());
        return page.map(report -> ModerationQueueItem.of(report, snapshots
                .getOrDefault(report.getTargetType(), Map.of())
                .getOrDefault(report.getTargetId(), ModerationTargetSnapshot.gone())));
    }

    private Map<ReportTargetType, Map<UUID, ModerationTargetSnapshot>> loadSnapshots(List<ContentReport> reports) {
        Map<ReportTargetType, List<UUID>> idsByType = reports.stream().collect(Collectors.groupingBy(
                ContentReport::getTargetType, () -> new EnumMap<>(ReportTargetType.class),
                Collectors.mapping(ContentReport::getTargetId, Collectors.toList())));
        Map<ReportTargetType, Map<UUID, ModerationTargetSnapshot>> result = new EnumMap<>(ReportTargetType.class);
        idsByType.forEach((type, ids) -> result.put(type, snapshotsOf(type, List.copyOf(new HashSet<>(ids)))));
        return result;
    }

    private Map<UUID, ModerationTargetSnapshot> snapshotsOf(ReportTargetType type, List<UUID> ids) {
        return switch (type) {
            case REVIEW -> index(reviewRepository.findAllById(ids), Review::getId,
                    r -> ModerationTargetSnapshot.review(r.getComment(), r.getRating(), r.getModerationStatus()));
            case CLIENT_REVIEW -> index(clientReviewRepository.findAllById(ids), ClientReview::getId,
                    r -> ModerationTargetSnapshot.review(r.getComment(), r.getRating(), r.getModerationStatus()));
            case PORTFOLIO_PHOTO -> index(portfolioRows(ids), MediaFile::getId,
                    m -> ModerationTargetSnapshot.photo(m.getR2Url()));
            case MASTER_PROFILE -> index(masterRepository.findAllById(ids), Master::getId,
                    m -> ModerationTargetSnapshot.profile(fullName(m.displayFirstName(), m.displayLastName())));
            case SALON_PROFILE -> index(salonRepository.findAllById(ids), Salon::getId,
                    s -> ModerationTargetSnapshot.profile(s.getName()));
            case CLIENT_PROFILE -> index(userRepository.findAllById(ids), User::getId,
                    u -> ModerationTargetSnapshot.profile(fullName(u.getFirstName(), u.getLastName())));
        };
    }

    private List<MediaFile> portfolioRows(List<UUID> ids) {
        return mediaRepository.findAllById(ids).stream()
                .filter(m -> m.getMediaType() == MediaType.PORTFOLIO)
                .toList();
    }

    private static <T> Map<UUID, ModerationTargetSnapshot> index(
            Iterable<T> rows, Function<T, UUID> id, Function<T, ModerationTargetSnapshot> snapshot) {
        Map<UUID, ModerationTargetSnapshot> map = new HashMap<>();
        rows.forEach(row -> map.put(id.apply(row), snapshot.apply(row)));
        return map;
    }

    private static String fullName(String first, String last) {
        String joined = Stream.of(first, last).filter(Objects::nonNull).collect(Collectors.joining(" ")).strip();
        return joined.isEmpty() ? null : joined;
    }
}

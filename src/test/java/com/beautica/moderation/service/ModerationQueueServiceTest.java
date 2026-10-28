package com.beautica.moderation.service;

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
import com.beautica.review.entity.ModerationStatus;
import com.beautica.review.entity.Review;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ModerationQueueService — queue page + target snapshots (phase 396 D4)")
class ModerationQueueServiceTest {

    @Mock private ContentReportRepository contentReportRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private ClientReviewRepository clientReviewRepository;
    @Mock private MediaRepository mediaRepository;
    @Mock private MasterRepository masterRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private UserRepository userRepository;

    private ModerationQueueService service;

    @BeforeEach
    void setUp() {
        service = new ModerationQueueService(contentReportRepository, reviewRepository, clientReviewRepository,
                mediaRepository, masterRepository, salonRepository, userRepository);
    }

    @Test
    @DisplayName("OPEN → oldest first; one batch per target type; review / photo / salon snapshots; gone target flagged")
    void should_attachSnapshotsInOneBatchPerType_when_openPageListed() {
        UUID reviewId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID goneReviewId = UUID.randomUUID();
        List<ContentReport> reports = List.of(report(ReportTargetType.REVIEW, reviewId),
                report(ReportTargetType.REVIEW, reviewId), report(ReportTargetType.PORTFOLIO_PHOTO, photoId),
                report(ReportTargetType.SALON_PROFILE, salonId), report(ReportTargetType.REVIEW, goneReviewId));
        when(contentReportRepository.findByStatus(eq(ReportStatus.OPEN), any(Pageable.class)))
                .thenReturn(new PageImpl<>(reports, PageRequest.of(0, 20), 5));
        Review review = Review.builder().comment("Жахливо").rating((short) 1).build();
        ReflectionTestUtils.setField(review, "id", reviewId);
        review.moderate(ModerationStatus.HIDDEN_PENDING);
        when(reviewRepository.findAllById(anyList())).thenReturn(List.of(review));
        MediaFile photo = BeanUtils.instantiateClass(MediaFile.class);
        ReflectionTestUtils.setField(photo, "id", photoId);
        ReflectionTestUtils.setField(photo, "mediaType", MediaType.PORTFOLIO);
        ReflectionTestUtils.setField(photo, "r2Url", "https://pub.example.r2.dev/p.jpg");
        when(mediaRepository.findAllById(anyList())).thenReturn(List.of(photo));
        Salon salon = BeanUtils.instantiateClass(Salon.class);
        ReflectionTestUtils.setField(salon, "id", salonId);
        ReflectionTestUtils.setField(salon, "name", "Квітка");
        when(salonRepository.findAllById(anyList())).thenReturn(List.of(salon));

        Page<ModerationQueueItem> page = service.list(ReportStatus.OPEN, PageRequest.of(0, 20));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(contentReportRepository).findByStatus(eq(ReportStatus.OPEN), pageable.capture());
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by("createdAt").ascending());
        assertThat(page.getContent()).extracting(ModerationQueueItem::target).containsExactly(
                ModerationTargetSnapshot.review("Жахливо", (short) 1, ModerationStatus.HIDDEN_PENDING),
                ModerationTargetSnapshot.review("Жахливо", (short) 1, ModerationStatus.HIDDEN_PENDING),
                ModerationTargetSnapshot.photo("https://pub.example.r2.dev/p.jpg"),
                ModerationTargetSnapshot.profile("Квітка"),
                ModerationTargetSnapshot.gone());
        verify(reviewRepository).findAllById(anyList());
        verifyNoInteractions(clientReviewRepository, masterRepository, userRepository);
    }

    @Test
    @DisplayName("RESOLVED → newest resolution first (resolvedAt DESC, id DESC tie-break)")
    void should_sortNewestFirst_when_resolvedListed() {
        when(contentReportRepository.findByStatus(eq(ReportStatus.RESOLVED), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.list(ReportStatus.RESOLVED, PageRequest.of(1, 10));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(contentReportRepository).findByStatus(eq(ReportStatus.RESOLVED), pageable.capture());
        assertThat(pageable.getValue()).isEqualTo(PageRequest.of(1, 10,
                Sort.by(Sort.Order.desc("resolvedAt"), Sort.Order.desc("id"))));
    }

    @Test
    @DisplayName("snapshot text / name: bidi overrides, isolates, marks and line separators become visible \\uXXXX")
    void should_escapeBidiControls_when_snapshotBuilt() {
        String spoofed = "ok \u202Eevomer\u202C \u2066x\u2069\u200F\u2028end";

        ModerationTargetSnapshot review = ModerationTargetSnapshot.review(spoofed, (short) 2, ModerationStatus.VISIBLE);
        ModerationTargetSnapshot profile = ModerationTargetSnapshot.profile("\u202EName");

        assertThat(review.text()).isEqualTo("ok \\u202Eevomer\\u202C \\u2066x\\u2069\\u200F\\u2028end");
        assertThat(profile.displayName()).isEqualTo("\\u202EName");
        assertThat(ModerationTargetSnapshot.review("Звичайний текст", (short) 5, ModerationStatus.VISIBLE).text())
                .isEqualTo("Звичайний текст");
    }

    private static ContentReport report(ReportTargetType type, UUID targetId) {
        ContentReport report = BeanUtils.instantiateClass(ContentReport.class);
        ReflectionTestUtils.setField(report, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(report, "targetType", type);
        ReflectionTestUtils.setField(report, "targetId", targetId);
        ReflectionTestUtils.setField(report, "status", ReportStatus.OPEN);
        return report;
    }
}

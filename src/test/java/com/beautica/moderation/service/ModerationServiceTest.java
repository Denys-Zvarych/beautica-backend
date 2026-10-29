package com.beautica.moderation.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.media.service.MediaService;
import com.beautica.moderation.dto.ModerationAction;
import com.beautica.moderation.dto.ResolveReportRequest;
import com.beautica.moderation.dto.ResolveReportResponse;
import com.beautica.moderation.entity.ContentReport;
import com.beautica.moderation.entity.ReportResolution;
import com.beautica.moderation.entity.ReportStatus;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.repository.ContentReportRepository;
import com.beautica.moderation.repository.ModerationActionTokenRepository;
import com.beautica.review.entity.ModerationStatus;
import com.beautica.review.service.ReviewModerationService;
import com.beautica.review.service.ReviewModerationService.LockedReview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ModerationService — resolve actions per target (phase 396 D4)")
class ModerationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00.123456Z");
    private static final UUID REPORT_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();

    @Mock
    private ContentReportRepository repository;

    @Mock
    private ReviewModerationService reviewModerationService;

    @Mock
    private MediaService mediaService;

    @Mock
    private ProviderModerationLock providerLock;

    @Mock
    private ModerationActionTokenRepository actionTokenRepository;

    private ModerationService service;

    @BeforeEach
    void setUp() {
        service = new ModerationService(repository, actionTokenRepository, reviewModerationService, providerLock,
                mediaService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("REVIEW REMOVE → review REMOVED, OPEN reports REMOVED with the note, every report stamped target-gone")
    void should_removeReview_and_stampGone_when_removeOnReview() {
        stubReport(ReportTargetType.REVIEW, ReportStatus.OPEN);
        LockedReview locked = stubLockedReview();
        when(repository.resolveOpenOnTarget(ReportTargetType.REVIEW, TARGET_ID, ReportResolution.REMOVED,
                "abusive", NOW)).thenReturn(3);

        ResolveReportResponse response = service.resolve(REPORT_ID,
                new ResolveReportRequest(ModerationAction.REMOVE, "  abusive "));

        assertThat(response).isEqualTo(new ResolveReportResponse(
                ReportTargetType.REVIEW, TARGET_ID, ReportResolution.REMOVED, 3));
        verify(providerLock).lock(locked);
        verify(reviewModerationService).moderateLockedReview(locked, ModerationStatus.REMOVED);
        verify(repository).markTargetGone(ReportTargetType.REVIEW, TARGET_ID, NOW);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ModerationAction.class, names = {"RESTORE", "DISMISS"})
    @DisplayName("REVIEW RESTORE / DISMISS → review VISIBLE again, reports RESTORED / DISMISSED, not target-gone")
    void should_makeReviewVisible_when_restoreOrDismiss(ModerationAction action) {
        stubReport(ReportTargetType.REVIEW, ReportStatus.OPEN);
        LockedReview locked = stubLockedReview();
        stubResolved(1);
        ReportResolution expected = action == ModerationAction.RESTORE
                ? ReportResolution.RESTORED : ReportResolution.DISMISSED;

        ResolveReportResponse response = service.resolve(REPORT_ID, new ResolveReportRequest(action, null));

        assertThat(response.resolution()).isEqualTo(expected);
        verify(reviewModerationService).moderateLockedReview(locked, ModerationStatus.VISIBLE);
        verify(repository).resolveOpenOnTarget(ReportTargetType.REVIEW, TARGET_ID, expected, null, NOW);
        verify(repository, never()).markTargetGone(any(), any(), any());
    }

    @Test
    @DisplayName("CLIENT_REVIEW REMOVE → client review REMOVED")
    void should_removeClientReview_when_removeOnClientReview() {
        stubReport(ReportTargetType.CLIENT_REVIEW, ReportStatus.OPEN);
        when(reviewModerationService.moderateClientReview(TARGET_ID, ModerationStatus.REMOVED)).thenReturn(true);
        stubResolved(1);

        ResolveReportResponse response = service.resolve(REPORT_ID,
                new ResolveReportRequest(ModerationAction.REMOVE, null));

        assertThat(response.resolution()).isEqualTo(ReportResolution.REMOVED);
        verify(reviewModerationService).moderateClientReview(TARGET_ID, ModerationStatus.REMOVED);
    }

    @Test
    @DisplayName("review already deleted → reports TARGET_GONE, stamped gone")
    void should_resolveTargetGone_when_reviewMissing() {
        stubReport(ReportTargetType.REVIEW, ReportStatus.OPEN);
        when(reviewModerationService.lockReview(TARGET_ID)).thenReturn(Optional.empty());
        stubResolved(1);

        ResolveReportResponse response = service.resolve(REPORT_ID,
                new ResolveReportRequest(ModerationAction.DISMISS, null));

        assertThat(response.resolution()).isEqualTo(ReportResolution.TARGET_GONE);
        verify(repository).markTargetGone(ReportTargetType.REVIEW, TARGET_ID, NOW);
        verifyNoInteractions(providerLock);
    }

    @Test
    @DisplayName("PORTFOLIO_PHOTO REMOVE → media delete service (row + R2), REMOVED")
    void should_deletePhoto_when_removeOnPhoto() {
        stubReport(ReportTargetType.PORTFOLIO_PHOTO, ReportStatus.OPEN);
        when(mediaService.deletePortfolioPhotoForModeration(TARGET_ID)).thenReturn(true);
        stubResolved(1);

        ResolveReportResponse response = service.resolve(REPORT_ID,
                new ResolveReportRequest(ModerationAction.REMOVE, null));

        assertThat(response.resolution()).isEqualTo(ReportResolution.REMOVED);
        verify(mediaService).deletePortfolioPhotoForModeration(TARGET_ID);
        verify(repository).markTargetGone(ReportTargetType.PORTFOLIO_PHOTO, TARGET_ID, NOW);
    }

    @Test
    @DisplayName("PORTFOLIO_PHOTO DISMISS → photo untouched, DISMISSED")
    void should_keepPhoto_when_dismissOnPhoto() {
        stubReport(ReportTargetType.PORTFOLIO_PHOTO, ReportStatus.OPEN);
        stubResolved(1);

        ResolveReportResponse response = service.resolve(REPORT_ID,
                new ResolveReportRequest(ModerationAction.DISMISS, null));

        assertThat(response.resolution()).isEqualTo(ReportResolution.DISMISSED);
        verifyNoInteractions(mediaService, reviewModerationService);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ReportTargetType.class, names = {"MASTER_PROFILE", "SALON_PROFILE", "CLIENT_PROFILE"})
    @DisplayName("profile REMOVE → ACTIONED only (support contacts the owner), nothing deleted, not target-gone")
    void should_markActioned_when_removeOnProfile(ReportTargetType type) {
        stubReport(type, ReportStatus.OPEN);
        stubResolved(1);

        ResolveReportResponse response = service.resolve(REPORT_ID,
                new ResolveReportRequest(ModerationAction.REMOVE, null));

        assertThat(response.resolution()).isEqualTo(ReportResolution.ACTIONED);
        verifyNoInteractions(mediaService, reviewModerationService);
        verify(repository, never()).markTargetGone(any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ReportTargetType.class, names = {"PORTFOLIO_PHOTO", "MASTER_PROFILE", "SALON_PROFILE",
            "CLIENT_PROFILE"})
    @DisplayName("RESTORE on a non-review target → 400, nothing changed")
    void should_reject_when_restoreOnNonReview(ReportTargetType type) {
        stubReport(type, ReportStatus.OPEN);

        assertThatThrownBy(() -> service.resolve(REPORT_ID, new ResolveReportRequest(ModerationAction.RESTORE, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(repository, never()).resolveOpenOnTarget(any(), any(), any(), any(), any());
        verifyNoInteractions(mediaService, reviewModerationService);
    }

    @Test
    @DisplayName("already RESOLVED report → 409, nothing changed")
    void should_conflict_when_reportAlreadyResolved() {
        stubReport(ReportTargetType.REVIEW, ReportStatus.RESOLVED);

        assertThatThrownBy(() -> service.resolve(REPORT_ID, new ResolveReportRequest(ModerationAction.DISMISS, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verifyNoInteractions(reviewModerationService);
    }

    @Test
    @DisplayName("unknown report → 404")
    void should_notFound_when_reportMissing() {
        when(repository.findById(REPORT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(REPORT_ID, new ResolveReportRequest(ModerationAction.DISMISS, null)))
                .isInstanceOf(NotFoundException.class);

        verify(repository, never()).resolveOpenOnTarget(any(), any(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ModerationAction.class, names = {"RESTORE", "DISMISS"})
    @DisplayName("RESTORE / DISMISS on a REMOVED review → the 409 from the review side propagates, nothing resolved")
    void should_conflict_when_restoreOrDismissOnRemovedReview(ModerationAction action) {
        stubReport(ReportTargetType.REVIEW, ReportStatus.OPEN);
        LockedReview locked = stubLockedReview();
        doThrow(new BusinessException(HttpStatus.CONFLICT, "Removed content cannot be restored"))
                .when(reviewModerationService).moderateLockedReview(locked, ModerationStatus.VISIBLE);

        assertThatThrownBy(() -> service.resolve(REPORT_ID, new ResolveReportRequest(action, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(repository, never()).resolveOpenOnTarget(any(), any(), any(), any(), any());
        verify(repository, never()).markTargetGone(any(), any(), any());
    }

    @Test
    @DisplayName("a concurrent resolve closed every OPEN report first (0 rows updated) → 409, not a silent 200")
    void should_conflict_when_noOpenReportLeftToResolve() {
        stubReport(ReportTargetType.SALON_PROFILE, ReportStatus.OPEN);
        stubResolved(0);

        assertThatThrownBy(() -> service.resolve(REPORT_ID, new ResolveReportRequest(ModerationAction.DISMISS, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(repository, never()).markTargetGone(any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ModerationAction.class)
    @DisplayName("phase 414 D11: every successful resolve revokes all live e-mail action links of the target")
    void should_revokeActionLinks_when_resolved(ModerationAction action) {
        stubReport(ReportTargetType.REVIEW, ReportStatus.OPEN);
        stubLockedReview();
        stubResolved(1);

        service.resolve(REPORT_ID, new ResolveReportRequest(action, null));

        verify(actionTokenRepository).revokeAllForTarget(ReportTargetType.REVIEW, TARGET_ID, NOW);
    }

    @Test
    @DisplayName("phase 414 D11: a TARGET_GONE resolve revokes the links too")
    void should_revokeActionLinks_when_targetGone() {
        stubReport(ReportTargetType.REVIEW, ReportStatus.OPEN);
        when(reviewModerationService.lockReview(TARGET_ID)).thenReturn(Optional.empty());
        stubResolved(1);

        service.resolve(REPORT_ID, new ResolveReportRequest(ModerationAction.REMOVE, null));

        verify(actionTokenRepository).revokeAllForTarget(ReportTargetType.REVIEW, TARGET_ID, NOW);
    }

    @Test
    @DisplayName("phase 414 D11: a 409 resolve (nothing left to close) revokes nothing — its transaction rolls back")
    void should_notRevokeActionLinks_when_conflict() {
        stubReport(ReportTargetType.SALON_PROFILE, ReportStatus.OPEN);
        stubResolved(0);

        assertThatThrownBy(() -> service.resolve(REPORT_ID, new ResolveReportRequest(ModerationAction.DISMISS, null)))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(actionTokenRepository);
    }

    @Test
    @DisplayName("phase 414 D9: the oldest OPEN report on a target is looked up by target + OPEN status")
    void should_returnOldestOpenReportId_when_present() {
        ContentReport report = BeanUtils.instantiateClass(ContentReport.class);
        ReflectionTestUtils.setField(report, "id", REPORT_ID);
        when(repository.findFirstByTargetTypeAndTargetIdAndStatusOrderByCreatedAtAscIdAsc(
                ReportTargetType.REVIEW, TARGET_ID, ReportStatus.OPEN)).thenReturn(Optional.of(report));

        Optional<UUID> found = service.findOldestOpenReportId(ReportTargetType.REVIEW, TARGET_ID);

        assertThat(found).contains(REPORT_ID);
    }

    @Test
    @DisplayName("phase 414: the OPEN count is the repository count for the target in OPEN status")
    void should_countOpenReports_when_asked() {
        when(repository.countByTargetTypeAndTargetIdAndStatus(ReportTargetType.REVIEW, TARGET_ID, ReportStatus.OPEN))
                .thenReturn(4L);

        long count = service.countOpenReports(ReportTargetType.REVIEW, TARGET_ID);

        assertThat(count).isEqualTo(4L);
    }

    private LockedReview stubLockedReview() {
        LockedReview locked = new LockedReview(TARGET_ID, UUID.randomUUID(), null, ModerationStatus.HIDDEN_PENDING);
        when(reviewModerationService.lockReview(TARGET_ID)).thenReturn(Optional.of(locked));
        return locked;
    }

    private void stubResolved(int rows) {
        when(repository.resolveOpenOnTarget(any(), any(), any(), any(), any())).thenReturn(rows);
    }

    private void stubReport(ReportTargetType type, ReportStatus status) {
        ContentReport report = BeanUtils.instantiateClass(ContentReport.class);
        ReflectionTestUtils.setField(report, "id", REPORT_ID);
        ReflectionTestUtils.setField(report, "targetType", type);
        ReflectionTestUtils.setField(report, "targetId", TARGET_ID);
        ReflectionTestUtils.setField(report, "status", status);
        when(repository.findById(REPORT_ID)).thenReturn(Optional.of(report));
    }
}

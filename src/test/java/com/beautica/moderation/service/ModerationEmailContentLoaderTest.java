package com.beautica.moderation.service;

import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;
import com.beautica.media.repository.MediaRepository;
import com.beautica.media.service.R2StorageService;
import com.beautica.moderation.dto.ContentReportEmailModel;
import com.beautica.moderation.dto.ReportedContent;
import com.beautica.moderation.entity.ReportReason;
import com.beautica.moderation.entity.ReportStatus;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.event.ContentReportCreatedEvent;
import com.beautica.moderation.repository.ContentReportRepository;
import com.beautica.review.entity.ModerationStatus;
import com.beautica.review.entity.Review;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ModerationEmailContentLoader — e-mail content per target type (phase 414 D1/D2)")
class ModerationEmailContentLoaderTest {

    private static final UUID REPORT_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();

    @Mock private ContentReportRepository contentReportRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private ClientReviewRepository clientReviewRepository;
    @Mock private MediaRepository mediaRepository;
    @Mock private MasterRepository masterRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private UserRepository userRepository;
    @Mock private R2StorageService r2StorageService;

    private ModerationEmailContentLoader loader;
    private String comment;

    @BeforeEach
    void setUp() {
        loader = new ModerationEmailContentLoader(contentReportRepository, reviewRepository, clientReviewRepository,
                mediaRepository, masterRepository, salonRepository, userRepository, r2StorageService);
    }

    @Test
    @DisplayName("REVIEW: text bidi-escaped, stars, HIDDEN_PENDING flag; comment from the event (no re-read), "
            + "bidi-escaped; OPEN count")
    void should_loadReviewCommentAndCount_when_review() {
        stubReport(ReportTargetType.REVIEW, "see ‮txet", 3);
        Review review = entity(Review.class, Map.of("comment", "bad ⁦x", "rating", (short) 2,
                "moderationStatus", ModerationStatus.HIDDEN_PENDING));
        when(reviewRepository.findById(TARGET_ID)).thenReturn(Optional.of(review));

        ContentReportEmailModel model = loader.load(event(ReportTargetType.REVIEW));

        assertThat(model.content()).isEqualTo(new ReportedContent(true, "bad \\u2066x", 2, true, null, null, null, null, false));
        assertThat(model.reporterComment()).isEqualTo("see \\u202Etxet");
        assertThat(model.openReportCount()).isEqualTo(3);
        assertThat(model.actions()).isEmpty();
        verify(contentReportRepository, never()).findById(any());
    }

    @Test
    @DisplayName("PORTFOLIO_PHOTO: photoUrl = R2StorageService.buildPublicUrl(r2Key)")
    void should_useBuildPublicUrl_when_portfolioPhoto() {
        stubReport(ReportTargetType.PORTFOLIO_PHOTO, null, 1);
        MediaFile media = entity(MediaFile.class, Map.of("mediaType", MediaType.PORTFOLIO, "r2Key", "masters/m/p.webp"));
        when(mediaRepository.findById(TARGET_ID)).thenReturn(Optional.of(media));
        when(r2StorageService.buildPublicUrl("masters/m/p.webp")).thenReturn("https://pub.r2.test/masters/m/p.webp");

        ContentReportEmailModel model = loader.load(event(ReportTargetType.PORTFOLIO_PHOTO));

        assertThat(model.content().photoUrl()).isEqualTo("https://pub.r2.test/masters/m/p.webp");
    }

    @Test
    @DisplayName("MASTER_PROFILE: one JOIN FETCH read (findByIdWithUser), display name + avatar via buildPublicUrl")
    void should_loadNameAndAvatar_when_masterProfile() {
        stubReport(ReportTargetType.MASTER_PROFILE, null, 1);
        User user = entity(User.class, Map.of("firstName", "Олена", "lastName", "Ш", "avatarR2Key", "users/u/a.webp",
                "email", "master@beautica.test"));
        Master master = entity(Master.class, Map.of("user", user));
        when(masterRepository.findByIdWithUser(TARGET_ID)).thenReturn(Optional.of(master));
        when(r2StorageService.buildPublicUrl("users/u/a.webp")).thenReturn("https://pub.r2.test/users/u/a.webp");

        ContentReportEmailModel model = loader.load(event(ReportTargetType.MASTER_PROFILE));

        assertThat(model.content().displayName()).isEqualTo("Олена Ш");
        assertThat(model.content().avatarUrl()).isEqualTo("https://pub.r2.test/users/u/a.webp");
        assertThat(model.content().ownerEmail()).as("profile flow: the master's account e-mail")
                .isEqualTo("master@beautica.test");
        verify(masterRepository, never()).findById(any());
    }

    @Test
    @DisplayName("SALON_PROFILE: salon name + logo key via buildPublicUrl + the OWNER's e-mail as the contact")
    void should_loadSalonNameAndLogo_when_salonProfile() {
        stubReport(ReportTargetType.SALON_PROFILE, null, 1);
        User owner = entity(User.class, Map.of("email", "owner@beautica.test", "emailVerified", true));
        Salon salon = entity(Salon.class, Map.of("name", "Lux", "avatarR2Key", "salons/s/logo/l.webp", "owner", owner));
        when(salonRepository.findById(TARGET_ID)).thenReturn(Optional.of(salon));
        when(r2StorageService.buildPublicUrl("salons/s/logo/l.webp")).thenReturn("https://pub.r2.test/l.webp");

        ContentReportEmailModel model = loader.load(event(ReportTargetType.SALON_PROFILE));

        assertThat(model.content()).isEqualTo(
                ReportedContent.profile("Lux", "https://pub.r2.test/l.webp", "owner@beautica.test", true));
    }

    @Test
    @DisplayName("audit-fix: the master's emailVerified flag is loaded with the address")
    void should_carryVerifiedFlag_when_masterEmailVerified() {
        stubReport(ReportTargetType.MASTER_PROFILE, null, 1);
        User user = entity(User.class, Map.of("firstName", "Олена", "email", "m@beautica.test", "emailVerified", true));
        when(masterRepository.findByIdWithUser(TARGET_ID)).thenReturn(Optional.of(entity(Master.class,
                Map.of("user", user))));

        ContentReportEmailModel model = loader.load(event(ReportTargetType.MASTER_PROFILE));

        assertThat(model.content().ownerEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("CLIENT_PROFILE without avatar: name + the client's e-mail, R2 never asked")
    void should_loadNameWithoutAvatar_when_clientWithoutAvatar() {
        stubReport(ReportTargetType.CLIENT_PROFILE, null, 1);
        User user = entity(User.class, Map.of("firstName", "Іван", "email", "client@beautica.test"));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(user));

        ContentReportEmailModel model = loader.load(event(ReportTargetType.CLIENT_PROFILE));

        assertThat(model.content()).as("an unverified client address is carried, flagged unverified")
                .isEqualTo(ReportedContent.profile("Іван", null, "client@beautica.test", false));
    }

    @Test
    @DisplayName("audit-fix: URLs in the review text and the comment arrive defanged")
    void should_defangUrls_when_textHasLinks() {
        stubReport(ReportTargetType.REVIEW, "see https://evil.com/x", 1);
        Review review = entity(Review.class, Map.of("comment", "visit www.phish.example", "rating", (short) 1,
                "moderationStatus", ModerationStatus.VISIBLE));
        when(reviewRepository.findById(TARGET_ID)).thenReturn(Optional.of(review));

        ContentReportEmailModel model = loader.load(event(ReportTargetType.REVIEW));

        assertThat(model.content().text()).isEqualTo("visit www[.]phish[.]example");
        assertThat(model.reporterComment()).isEqualTo("see hxxps://evil[.]com/x");
    }

    @Test
    @DisplayName("missing target → gone")
    void should_beGone_when_targetMissing() {
        stubReport(ReportTargetType.CLIENT_REVIEW, null, 0);
        when(clientReviewRepository.findById(TARGET_ID)).thenReturn(Optional.empty());

        ContentReportEmailModel model = loader.load(event(ReportTargetType.CLIENT_REVIEW));

        assertThat(model.content()).isEqualTo(ReportedContent.gone());
    }

    private void stubReport(ReportTargetType type, String comment, long open) {
        this.comment = comment;
        when(contentReportRepository.countByTargetTypeAndTargetIdAndStatus(type, TARGET_ID, ReportStatus.OPEN))
                .thenReturn(open);
    }

    private ContentReportCreatedEvent event(ReportTargetType type) {
        return new ContentReportCreatedEvent(REPORT_ID, type, TARGET_ID, ReportReason.SPAM, UUID.randomUUID(), false,
                comment);
    }

    private static <T> T entity(Class<T> type, Map<String, Object> fields) {
        T instance = BeanUtils.instantiateClass(type);
        fields.forEach((name, value) -> ReflectionTestUtils.setField(instance, name, value));
        return instance;
    }
}

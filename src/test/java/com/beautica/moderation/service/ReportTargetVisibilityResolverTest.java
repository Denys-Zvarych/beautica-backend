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
import com.beautica.moderation.service.ReportTargetVisibilityResolver.ReportTarget;
import com.beautica.review.entity.ClientReview;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReportTargetVisibilityResolver — unit (phase 395 D2/D5)")
class ReportTargetVisibilityResolverTest {

    private static final UUID REPORTER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();

    @Mock private ReviewRepository reviewRepository;
    @Mock private ClientReviewRepository clientReviewRepository;
    @Mock private MediaRepository mediaRepository;
    @Mock private MasterRepository masterRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private UserRepository userRepository;
    @Mock private BookingRepository bookingRepository;

    private ReportTargetVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ReportTargetVisibilityResolver(reviewRepository, clientReviewRepository, mediaRepository,
                masterRepository, salonRepository, userRepository, bookingRepository);
    }

    @Nested
    @DisplayName("REVIEW")
    class ReviewTarget {

        @Test
        @DisplayName("existing review by someone else → visible, not owned")
        void should_beVisible_when_reviewExists() {
            when(reviewRepository.findById(TARGET_ID)).thenReturn(Optional.of(reviewBy(OTHER_USER_ID)));

            Optional<ReportTarget> result = resolve(ReportTargetType.REVIEW, Role.SALON_MASTER);

            assertThat(result).contains(new ReportTarget(false));
        }

        @Test
        @DisplayName("missing (deleted) review → empty (404)")
        void should_beEmpty_when_reviewMissing() {
            when(reviewRepository.findById(TARGET_ID)).thenReturn(Optional.empty());

            Optional<ReportTarget> result = resolve(ReportTargetType.REVIEW, Role.CLIENT);

            assertThat(result).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ModerationStatus.class, names = "VISIBLE", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("phase 396: a hidden or removed review → empty (same 404 as missing, no longer reportable)")
        void should_beEmpty_when_reviewNotVisible(ModerationStatus status) {
            Review review = reviewBy(OTHER_USER_ID);
            review.moderate(status);
            when(reviewRepository.findById(TARGET_ID)).thenReturn(Optional.of(review));

            Optional<ReportTarget> result = resolve(ReportTargetType.REVIEW, Role.CLIENT);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("the reporter's own review → owned")
        void should_beOwned_when_reporterWroteReview() {
            when(reviewRepository.findById(TARGET_ID)).thenReturn(Optional.of(reviewBy(REPORTER_ID)));

            Optional<ReportTarget> result = resolve(ReportTargetType.REVIEW, Role.CLIENT);

            assertThat(result).contains(new ReportTarget(true));
        }
    }

    @Nested
    @DisplayName("CLIENT_PROFILE — only through a shared booking")
    class ClientProfileTarget {

        @Test
        @DisplayName("provider with a booking with that client → visible")
        void should_beVisible_when_providerSharesBooking() {
            when(bookingRepository.existsClientBookingVisibleToProvider(TARGET_ID, REPORTER_ID, false))
                    .thenReturn(true);

            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_PROFILE, Role.INDEPENDENT_MASTER);

            assertThat(result).contains(new ReportTarget(false));
        }

        @Test
        @DisplayName("provider without a shared booking → empty (404)")
        void should_beEmpty_when_noSharedBooking() {
            when(bookingRepository.existsClientBookingVisibleToProvider(TARGET_ID, REPORTER_ID, false))
                    .thenReturn(false);

            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_PROFILE, Role.SALON_OWNER);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("SALON_MASTER → the booking check requires the master row to be active")
        void should_requireActiveMaster_when_reporterIsSalonMaster() {
            when(bookingRepository.existsClientBookingVisibleToProvider(TARGET_ID, REPORTER_ID, true))
                    .thenReturn(true);

            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_PROFILE, Role.SALON_MASTER);

            assertThat(result).contains(new ReportTarget(false));
            verify(bookingRepository).existsClientBookingVisibleToProvider(TARGET_ID, REPORTER_ID, true);
        }

        @Test
        @DisplayName("SALON_ADMIN → a booking of that client at the admin's assigned salon")
        void should_checkAssignedSalon_when_reporterIsSalonAdmin() {
            UUID salonId = UUID.randomUUID();
            when(userRepository.findSalonIdById(REPORTER_ID)).thenReturn(Optional.of(salonId));
            when(bookingRepository.existsByClientIdAndSalonId(TARGET_ID, salonId)).thenReturn(true);

            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_PROFILE, Role.SALON_ADMIN);

            assertThat(result).contains(new ReportTarget(false));
            verify(bookingRepository, never()).existsClientBookingVisibleToProvider(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("SALON_ADMIN without an assigned salon → empty")
        void should_beEmpty_when_adminHasNoSalon() {
            when(userRepository.findSalonIdById(REPORTER_ID)).thenReturn(Optional.empty());

            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_PROFILE, Role.SALON_ADMIN);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("a CLIENT never sees another client's profile → empty, no booking query")
        void should_beEmpty_when_reporterIsClient() {
            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_PROFILE, Role.CLIENT);

            assertThat(result).isEmpty();
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("own client profile → owned")
        void should_beOwned_when_targetIsReporter() {
            Optional<ReportTarget> result =
                    resolver.resolve(ReportTargetType.CLIENT_PROFILE, REPORTER_ID, REPORTER_ID, Role.CLIENT);

            assertThat(result).contains(new ReportTarget(true));
        }
    }

    @Nested
    @DisplayName("CLIENT_REVIEW — only through a shared booking with the reviewed client")
    class ClientReviewTarget {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ModerationStatus.class, names = "VISIBLE", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("phase 396: a hidden or removed client review → empty, before any booking lookup")
        void should_beEmpty_when_clientReviewNotVisible(ModerationStatus status) {
            ClientReview review = clientReview(OTHER_USER_ID, UUID.randomUUID());
            review.moderate(status);
            when(clientReviewRepository.findById(TARGET_ID)).thenReturn(Optional.of(review));

            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_REVIEW, Role.SALON_OWNER);

            assertThat(result).isEmpty();
            verifyNoInteractions(bookingRepository);
        }


        @Test
        @DisplayName("provider with a booking with the reviewed client → visible")
        void should_beVisible_when_providerSharesBookingWithSubject() {
            UUID subjectId = UUID.randomUUID();
            when(clientReviewRepository.findById(TARGET_ID))
                    .thenReturn(Optional.of(clientReview(OTHER_USER_ID, subjectId)));
            when(bookingRepository.existsClientBookingVisibleToProvider(subjectId, REPORTER_ID, false))
                    .thenReturn(true);

            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_REVIEW, Role.SALON_OWNER);

            assertThat(result).contains(new ReportTarget(false));
        }

        @Test
        @DisplayName("provider without a booking with the reviewed client → empty")
        void should_beEmpty_when_noSharedBookingWithSubject() {
            UUID subjectId = UUID.randomUUID();
            when(clientReviewRepository.findById(TARGET_ID))
                    .thenReturn(Optional.of(clientReview(OTHER_USER_ID, subjectId)));
            when(bookingRepository.existsClientBookingVisibleToProvider(subjectId, REPORTER_ID, false))
                    .thenReturn(false);

            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_REVIEW, Role.INDEPENDENT_MASTER);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("the reviewed client (CLIENT role) cannot see the text → empty")
        void should_beEmpty_when_reporterIsTheReviewedClient() {
            when(clientReviewRepository.findById(TARGET_ID))
                    .thenReturn(Optional.of(clientReview(OTHER_USER_ID, REPORTER_ID)));

            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_REVIEW, Role.CLIENT);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("missing client review → empty")
        void should_beEmpty_when_clientReviewMissing() {
            when(clientReviewRepository.findById(TARGET_ID)).thenReturn(Optional.empty());

            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_REVIEW, Role.SALON_OWNER);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("the authoring master → owned")
        void should_beOwned_when_reporterAuthoredIt() {
            when(clientReviewRepository.findById(TARGET_ID))
                    .thenReturn(Optional.of(clientReview(REPORTER_ID, UUID.randomUUID())));

            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_REVIEW, Role.INDEPENDENT_MASTER);

            assertThat(result).contains(new ReportTarget(true));
        }

        @Test
        @DisplayName("owner of the booking's salon (wrote it under the performing master) → owned")
        void should_beOwned_when_reporterOwnsReviewSalon() {
            UUID salonId = UUID.randomUUID();
            when(clientReviewRepository.findById(TARGET_ID))
                    .thenReturn(Optional.of(salonClientReview(OTHER_USER_ID, UUID.randomUUID(), salonId)));
            when(salonRepository.existsByIdAndOwnerId(salonId, REPORTER_ID)).thenReturn(true);

            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_REVIEW, Role.SALON_OWNER);

            assertThat(result).contains(new ReportTarget(true));
            verify(bookingRepository, never()).existsClientBookingVisibleToProvider(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("assigned admin of the booking's salon → owned")
        void should_beOwned_when_reporterIsAssignedAdminOfReviewSalon() {
            UUID salonId = UUID.randomUUID();
            when(clientReviewRepository.findById(TARGET_ID))
                    .thenReturn(Optional.of(salonClientReview(OTHER_USER_ID, UUID.randomUUID(), salonId)));
            when(userRepository.findSalonIdById(REPORTER_ID)).thenReturn(Optional.of(salonId));

            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_REVIEW, Role.SALON_ADMIN);

            assertThat(result).contains(new ReportTarget(true));
        }

        @Test
        @DisplayName("salon master of the booking's salon (not the author) → visible, not owned")
        void should_notBeOwned_when_reporterIsSalonMasterOfReviewSalon() {
            UUID salonId = UUID.randomUUID();
            UUID subjectId = UUID.randomUUID();
            when(clientReviewRepository.findById(TARGET_ID))
                    .thenReturn(Optional.of(salonClientReview(OTHER_USER_ID, subjectId, salonId)));
            when(salonRepository.existsByIdAndOwnerId(salonId, REPORTER_ID)).thenReturn(false);
            when(bookingRepository.existsClientBookingVisibleToProvider(subjectId, REPORTER_ID, true))
                    .thenReturn(true);

            Optional<ReportTarget> result = resolve(ReportTargetType.CLIENT_REVIEW, Role.SALON_MASTER);

            assertThat(result).contains(new ReportTarget(false));
        }
    }

    @Nested
    @DisplayName("PORTFOLIO_PHOTO")
    class PortfolioPhotoTarget {

        @Test
        @DisplayName("someone else's master portfolio photo → visible, not owned")
        void should_beVisible_when_masterPortfolioPhoto() {
            UUID masterId = UUID.randomUUID();
            when(mediaRepository.findById(TARGET_ID))
                    .thenReturn(Optional.of(media(MediaType.PORTFOLIO, EntityType.MASTER, masterId)));
            when(masterRepository.existsByIdAndUserId(masterId, REPORTER_ID)).thenReturn(false);

            Optional<ReportTarget> result = resolve(ReportTargetType.PORTFOLIO_PHOTO, Role.CLIENT);

            assertThat(result).contains(new ReportTarget(false));
        }

        @Test
        @DisplayName("own master portfolio photo → owned")
        void should_beOwned_when_reporterIsPhotoMaster() {
            UUID masterId = UUID.randomUUID();
            when(mediaRepository.findById(TARGET_ID))
                    .thenReturn(Optional.of(media(MediaType.PORTFOLIO, EntityType.MASTER, masterId)));
            when(masterRepository.existsByIdAndUserId(masterId, REPORTER_ID)).thenReturn(true);

            Optional<ReportTarget> result = resolve(ReportTargetType.PORTFOLIO_PHOTO, Role.INDEPENDENT_MASTER);

            assertThat(result).contains(new ReportTarget(true));
        }

        @Test
        @DisplayName("salon portfolio photo of the reporter's own salon → owned")
        void should_beOwned_when_reporterOwnsPhotoSalon() {
            UUID salonId = UUID.randomUUID();
            when(mediaRepository.findById(TARGET_ID))
                    .thenReturn(Optional.of(media(MediaType.PORTFOLIO, EntityType.SALON, salonId)));
            when(salonRepository.existsByIdAndOwnerId(salonId, REPORTER_ID)).thenReturn(true);

            Optional<ReportTarget> result = resolve(ReportTargetType.PORTFOLIO_PHOTO, Role.SALON_OWNER);

            assertThat(result).contains(new ReportTarget(true));
        }

        @Test
        @DisplayName("salon portfolio photo seen by one of its SALON_MASTERs → visible, not owned")
        void should_notBeOwned_when_reporterIsSalonMasterOfPhotoSalon() {
            UUID salonId = UUID.randomUUID();
            when(mediaRepository.findById(TARGET_ID))
                    .thenReturn(Optional.of(media(MediaType.PORTFOLIO, EntityType.SALON, salonId)));
            when(salonRepository.existsByIdAndOwnerId(salonId, REPORTER_ID)).thenReturn(false);

            Optional<ReportTarget> result = resolve(ReportTargetType.PORTFOLIO_PHOTO, Role.SALON_MASTER);

            assertThat(result).contains(new ReportTarget(false));
        }

        @Test
        @DisplayName("an AVATAR media row is not a portfolio photo → empty")
        void should_beEmpty_when_mediaIsAvatar() {
            when(mediaRepository.findById(TARGET_ID))
                    .thenReturn(Optional.of(media(MediaType.AVATAR, EntityType.USER, UUID.randomUUID())));

            Optional<ReportTarget> result = resolve(ReportTargetType.PORTFOLIO_PHOTO, Role.CLIENT);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("missing media → empty")
        void should_beEmpty_when_mediaMissing() {
            when(mediaRepository.findById(TARGET_ID)).thenReturn(Optional.empty());

            Optional<ReportTarget> result = resolve(ReportTargetType.PORTFOLIO_PHOTO, Role.CLIENT);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("MASTER_PROFILE")
    class MasterProfileTarget {

        @Test
        @DisplayName("attached master of another user → visible")
        void should_beVisible_when_masterAttached() {
            when(masterRepository.findById(TARGET_ID)).thenReturn(Optional.of(masterOf(user(OTHER_USER_ID))));

            Optional<ReportTarget> result = resolve(ReportTargetType.MASTER_PROFILE, Role.CLIENT);

            assertThat(result).contains(new ReportTarget(false));
        }

        @Test
        @DisplayName("detached master (account gone, public profile 404s) → empty")
        void should_beEmpty_when_masterDetached() {
            when(masterRepository.findById(TARGET_ID)).thenReturn(Optional.of(masterOf(null)));

            Optional<ReportTarget> result = resolve(ReportTargetType.MASTER_PROFILE, Role.CLIENT);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("own master row → owned")
        void should_beOwned_when_reporterIsMaster() {
            when(masterRepository.findById(TARGET_ID)).thenReturn(Optional.of(masterOf(user(REPORTER_ID))));

            Optional<ReportTarget> result = resolve(ReportTargetType.MASTER_PROFILE, Role.SALON_OWNER);

            assertThat(result).contains(new ReportTarget(true));
        }
    }

    @Nested
    @DisplayName("SALON_PROFILE")
    class SalonProfileTarget {

        @Test
        @DisplayName("active salon of another owner → visible")
        void should_beVisible_when_salonActive() {
            when(salonRepository.findById(TARGET_ID)).thenReturn(Optional.of(salon(true, user(OTHER_USER_ID))));

            Optional<ReportTarget> result = resolve(ReportTargetType.SALON_PROFILE, Role.CLIENT);

            assertThat(result).contains(new ReportTarget(false));
        }

        @Test
        @DisplayName("inactive (deleted) salon → empty")
        void should_beEmpty_when_salonInactive() {
            when(salonRepository.findById(TARGET_ID)).thenReturn(Optional.of(salon(false, user(OTHER_USER_ID))));

            Optional<ReportTarget> result = resolve(ReportTargetType.SALON_PROFILE, Role.CLIENT);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("own salon → owned")
        void should_beOwned_when_reporterOwnsSalon() {
            when(salonRepository.findById(TARGET_ID)).thenReturn(Optional.of(salon(true, user(REPORTER_ID))));

            Optional<ReportTarget> result = resolve(ReportTargetType.SALON_PROFILE, Role.SALON_OWNER);

            assertThat(result).contains(new ReportTarget(true));
        }

        @Test
        @DisplayName("the salon's assigned SALON_ADMIN → owned")
        void should_beOwned_when_reporterIsAssignedAdmin() {
            when(salonRepository.findById(TARGET_ID)).thenReturn(Optional.of(salon(true, user(OTHER_USER_ID))));
            when(userRepository.findSalonIdById(REPORTER_ID)).thenReturn(Optional.of(TARGET_ID));

            Optional<ReportTarget> result = resolve(ReportTargetType.SALON_PROFILE, Role.SALON_ADMIN);

            assertThat(result).contains(new ReportTarget(true));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ReportTargetType.class)
    @DisplayName("an unknown id resolves to empty for every target type (identical 404)")
    void should_beEmpty_when_idUnknown(ReportTargetType type) {
        Optional<ReportTarget> result = resolve(type, Role.SALON_OWNER);

        assertThat(result).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Optional<ReportTarget> resolve(ReportTargetType type, Role role) {
        return resolver.resolve(type, TARGET_ID, REPORTER_ID, role);
    }

    /** A real (not mocked) User: fixtures are built inside {@code thenReturn(...)}, where a nested stub would break. */
    private static User user(UUID id) {
        User user = BeanUtils.instantiateClass(User.class);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Review reviewBy(UUID clientId) {
        return Review.builder().client(user(clientId)).build();
    }

    private static ClientReview clientReview(UUID authorUserId, UUID subjectClientId) {
        Master author = masterOf(user(authorUserId));
        return ClientReview.builder().authorMaster(author).subjectClient(user(subjectClientId)).build();
    }

    private static ClientReview salonClientReview(UUID authorUserId, UUID subjectClientId, UUID salonId) {
        return ClientReview.builder()
                .authorMaster(masterOf(user(authorUserId)))
                .subjectClient(user(subjectClientId))
                .salon(Salon.builder().id(salonId).build())
                .build();
    }

    private static Master masterOf(User user) {
        return Master.builder().user(user).isActive(true).build();
    }

    private static Salon salon(boolean active, User owner) {
        return Salon.builder().id(TARGET_ID).owner(owner).isActive(active).build();
    }

    private static MediaFile media(MediaType mediaType, EntityType entityType, UUID entityId) {
        return MediaFile.builder().mediaType(mediaType).entityType(entityType).entityId(entityId).build();
    }
}

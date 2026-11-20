package com.beautica.notification.inapp.service;

import com.beautica.auth.Role;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.security.AuthorizationService;
import com.beautica.notification.inapp.dto.NotificationParams;
import com.beautica.notification.inapp.dto.NotificationResponse;
import com.beautica.notification.inapp.dto.NotificationTarget;
import com.beautica.notification.inapp.entity.InAppNotification;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceTypeSuggestionRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B516 audit-fix cycle 2 (AUTH-D19) — a security notice shows the masked value stored on the row when the change
 * happened, never the account's CURRENT phone / email (a later change would otherwise rewrite every older notice).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationViewAssembler — security notices show the value at the time of the change (B516)")
class NotificationViewAssemblerSecurityNoticeTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-14T10:00:00Z");
    private static final String MASKED_AT_CHANGE = "+380*****2222";
    private static final String CURRENT_PHONE = "+380673333333";

    @Mock private BookingRepository bookingRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private UserRepository userRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private ServiceTypeSuggestionRepository suggestionRepository;
    @Mock private AuthorizationService authorizationService;

    private NotificationViewAssembler assembler;
    private UUID userId;

    @BeforeEach
    void setUp() {
        assembler = new NotificationViewAssembler(bookingRepository, salonRepository, userRepository,
                reviewRepository, platformCategoryRepository, suggestionRepository, authorizationService,
                Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC));
        userId = UUID.randomUUID();
        User account = User.registerWithVerifiedPhone(CURRENT_PHONE, "hash", Role.CLIENT, "Olena", null, null,
                CREATED_AT);
        ReflectionTestUtils.setField(account, "id", userId);
        when(userRepository.findAllById(any())).thenReturn(List.of(account));
    }

    @Test
    @DisplayName("a phone notice shows the number masked at the time of the change, not the account's current number")
    void should_showStoredMaskedValue_when_phoneChangedAgainSince() {
        InAppNotification row = securityRow(InAppNotificationType.SECURITY_PHONE_CHANGED, MASKED_AT_CHANGE);

        NotificationResponse view = assembler.assemble(List.of(row), userId, Role.CLIENT).get(0);

        assertThat(view.params()).isEqualTo(NotificationParams.securityNotice(MASKED_AT_CHANGE));
        assertThat(view.target()).isEqualTo(NotificationTarget.none(null));
    }

    @Test
    @DisplayName("an email notice shows the address masked at the time of the change")
    void should_showStoredMaskedEmail_when_emailNotice() {
        InAppNotification row = securityRow(InAppNotificationType.SECURITY_EMAIL_CHANGED, "n***@beautica.test");

        NotificationResponse view = assembler.assemble(List.of(row), userId, Role.CLIENT).get(0);

        assertThat(view.params()).isEqualTo(NotificationParams.securityNotice("n***@beautica.test"));
    }

    @Test
    @DisplayName("an older notice with no stored value shows no value at all")
    void should_showNoParams_when_rowHasNoStoredValue() {
        InAppNotification row = securityRow(InAppNotificationType.SECURITY_PHONE_CHANGED, null);

        NotificationResponse view = assembler.assemble(List.of(row), userId, Role.CLIENT).get(0);

        assertThat(view.params()).isNull();
    }

    @Test
    @DisplayName("a feed of security notices only loads no user rows")
    void should_notLoadSubjectUsers_when_onlySecurityNotices() {
        InAppNotification row = securityRow(InAppNotificationType.SECURITY_PHONE_CHANGED, MASKED_AT_CHANGE);

        assembler.assemble(List.of(row), userId, Role.CLIENT);

        verify(userRepository, never()).findAllById(any());
    }

    private InAppNotification securityRow(InAppNotificationType type, String maskedContact) {
        return InAppNotification.builder()
                .id(UUID.randomUUID())
                .recipientUserId(userId)
                .type(type)
                .subjectUserId(userId)
                .maskedContact(maskedContact)
                .dedupKey(type.name() + ":" + userId + ":" + CREATED_AT.getEpochSecond())
                .createdAt(CREATED_AT)
                .build();
    }
}

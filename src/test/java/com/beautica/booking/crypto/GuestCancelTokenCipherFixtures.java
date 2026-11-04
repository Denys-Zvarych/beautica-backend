package com.beautica.booking.crypto;

import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.service.GuestBookingPageLinks;
import com.beautica.booking.service.GuestCancelUrlBuilder;
import org.mockito.Mockito;

/** Phase 435 — one real {@link GuestCancelTokenCipher} over the test-profile key, shared by unit tests. */
public final class GuestCancelTokenCipherFixtures {

    /** Same value as {@code app.guest-cancel-token.key} in {@code application-test.yml}. */
    public static final String TEST_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

    private GuestCancelTokenCipherFixtures() {
    }

    public static GuestCancelTokenCipher testCipher() {
        return new GuestCancelTokenCipher(new GuestCancelTokenCipherProperties(TEST_KEY));
    }

    /** A real {@link GuestBookingPageLinks} over {@link #testCipher()}; the header-probe repository is a mock. */
    public static GuestBookingPageLinks testPageLinks(String frontendBaseUrl) {
        return new GuestBookingPageLinks(testCipher(), new GuestCancelUrlBuilder(frontendBaseUrl),
                Mockito.mock(AppointmentRepository.class));
    }
}

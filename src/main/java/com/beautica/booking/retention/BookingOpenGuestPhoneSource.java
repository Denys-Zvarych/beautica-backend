package com.beautica.booking.retention;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.notification.telegram.link.OpenGuestPhoneSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Phase 409 — the booking side of the Telegram orphan backstop: one bounded keyset page per call. */
@Component
@RequiredArgsConstructor
public class BookingOpenGuestPhoneSource implements OpenGuestPhoneSource {

    private final BookingRepository bookingRepository;

    @Override
    @Transactional(readOnly = true)
    public List<String> openGuestPhonesPage(String after, int limit) {
        return bookingRepository.findOpenGuestPhonesPage(after, limit);
    }
}

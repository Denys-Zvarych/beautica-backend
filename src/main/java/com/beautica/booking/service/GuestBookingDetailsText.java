package com.beautica.booking.service;

import com.beautica.booking.domain.GuestConfirmationSmsText;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.BookingSmsProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The «booking confirmed» details a guest gets in Telegram right after linking the bot (sent after the LINKED reply).
 * One plain-text message, no {@code parse_mode}.
 *
 * <p><b>Phase 435 follow-up (user decision 2026-11-04, "same sms should be in telegram").</b> The text IS the guest's
 * confirmation SMS, rendered by the same {@link GuestConfirmationSmsText} from the same templates — the walk-in copy
 * ({@code walk-in-confirmation[-salon]}) for a STAFF booking, the LINK copy ({@code confirmation[-salon]}) otherwise —
 * with the same {@code GuestSmsProviderText} sanitising of every provider value (closes the backlog INFO about raw
 * salon name / address in this message):
 *
 * <pre>
 * Beautica: Ваш запис підтверджено!
 *
 * 26.10.2026 о 10:00
 * Салон Beauty Room, майстер Марія Левченко.
 * Адреса: Хрещатик 10 (вхід з двору)
 * Скасувати: https://beautica.app/c/{token}
 * </pre>
 *
 * <p><b>Cancel link.</b> Rebuilt from the sealed token ({@link GuestBookingPageLinks}, the visit header's for a visit);
 * the «Скасувати:» line is dropped when it cannot be (a row created before V230). No booking note is ever rendered.
 * The text is never logged.
 *
 * <p><b>Phone re-check.</b> The proof bound a phone hash to the token's booking; if that booking's guest phone has
 * changed since (e.g. a provider edit), the proven chat is no longer the booking's guest and nothing is rendered.
 */
@Component
public class GuestBookingDetailsText {

    private static final Comparator<Booking> VISIT_ORDER =
            Comparator.comparing(Booking::getStartsAt).thenComparing(Booking::getId);

    private final BookingRepository bookingRepository;
    private final PhoneHasher phoneHasher;
    private final Clock clock;
    private final BookingSmsProperties smsProperties;
    private final GuestBookingPageLinks pageLinks;

    public GuestBookingDetailsText(BookingRepository bookingRepository, PhoneHasher phoneHasher, Clock clock,
                                   BookingSmsProperties smsProperties, GuestBookingPageLinks pageLinks) {
        this.bookingRepository = bookingRepository;
        this.phoneHasher = phoneHasher;
        this.clock = clock;
        this.smsProperties = smsProperties;
        this.pageLinks = pageLinks;
    }

    /**
     * Renders the details of a guest booking (or of its whole visit), or empty when there is nothing to confirm: no
     * such booking, an account booking, a guest phone that no longer hashes to {@code provenPhoneHash}, nothing still
     * CONFIRMED, or a visit that has already started. One statement loads the booking and its whole visit.
     */
    @Transactional(readOnly = true)
    public Optional<String> render(UUID bookingId, byte[] provenPhoneHash) {
        List<Booking> rows = bookingRepository.findWithVisitForGuestDetails(bookingId);
        return rows.stream()
                .filter(row -> row.getId().equals(bookingId))
                .findFirst()
                .filter(lead -> lead.getClient() == null && guestPhoneMatches(lead, provenPhoneHash))
                .map(lead -> confirmedItems(lead, rows))
                .filter(items -> !items.isEmpty())
                .filter(items -> items.get(0).getStartsAt().toInstant().isAfter(clock.instant()))
                .map(this::format);
    }

    private boolean guestPhoneMatches(Booking lead, byte[] provenPhoneHash) {
        return provenPhoneHash != null && phoneHasher.tryHash(lead.getGuestPhone())
                .map(current -> MessageDigest.isEqual(current.hash(), provenPhoneHash))
                .orElse(false);
    }

    /** The lead alone, or every CONFIRMED item of its visit (already in {@code rows}), in visit order. */
    private static List<Booking> confirmedItems(Booking lead, List<Booking> rows) {
        Stream<Booking> items = lead.getAppointment() == null ? Stream.of(lead) : rows.stream();
        return items.filter(item -> item.getStatus() == BookingStatus.CONFIRMED).sorted(VISIT_ORDER).toList();
    }

    /** The confirmation SMS text for the visit's first CONFIRMED item — the walk-in copy for a STAFF booking. */
    String format(List<Booking> items) {
        Booking first = items.get(0);
        BookingSmsProperties.Sms sms = smsProperties.getSms();
        boolean walkIn = first.getBookingSource() == BookingSource.STAFF;
        return GuestConfirmationSmsText.render(
                walkIn ? sms.getWalkInConfirmationSalon() : sms.getConfirmationSalon(),
                walkIn ? sms.getWalkInConfirmation() : sms.getConfirmation(),
                first, pageLinks.forBooking(first).orElse(null));
    }
}

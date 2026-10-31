package com.beautica.booking.service;

import com.beautica.booking.domain.GuestSmsServiceName;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.TimeZones;
import com.beautica.common.security.PhoneHasher;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.PriceType;
import com.beautica.service.util.PriceDisplayFormatter;
import com.beautica.user.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The «booking confirmed» details a guest gets in Telegram right after linking the bot (sent after the LINKED reply).
 * One plain-text message, no {@code parse_mode}:
 *
 * <pre>
 * Запис підтверджено ✅
 * Послуга: Манікюр            (Послуги: Манікюр, Педикюр — for a visit)
 * Дата: 26.10.2026 о 10:00
 * Майстер: Марія Левченко
 * Салон: Beauty Room          (salon bookings only)
 * Адреса: вул. Хрещатик, 1    (when the salon / independent master has one)
 * Ціна: 350 ₴                 (від 300 до 500 ₴ for a price band)
 * </pre>
 *
 * <p><b>Reuse.</b> Kyiv wall clock and {@code dd.MM.yyyy} / {@code HH:mm} like every guest SMS; service names via
 * {@link GuestSmsServiceName} (platform name for a walk-in); price via {@link PriceDisplayFormatter} with the same
 * frozen floor/ceiling sum as {@code AppointmentDetailResponse}; address with the same salon-wins rule.
 *
 * <p><b>No cancel link.</b> The cancel token is stored only as a hash (hash at rest), so the raw link cannot be
 * rebuilt here; the guest already has it in the confirmation SMS and on the booking page. No booking note is ever
 * rendered. The text is never logged.
 *
 * <p><b>Phone re-check.</b> The proof bound a phone hash to the token's booking; if that booking's guest phone has
 * changed since (e.g. a provider edit), the proven chat is no longer the booking's guest and nothing is rendered.
 */
@Component
public class GuestBookingDetailsText {

    static final String HEADING = "Запис підтверджено ✅";
    static final String SERVICE = "Послуга: ";
    static final String SERVICES = "Послуги: ";
    static final String DATE = "Дата: ";
    static final String AT = " о ";
    static final String MASTER = "Майстер: ";
    static final String SALON = "Салон: ";
    static final String ADDRESS = "Адреса: ";
    static final String PRICE = "Ціна: ";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String NEWLINE = "\n";
    private static final String LIST_SEPARATOR = ", ";
    private static final Comparator<Booking> VISIT_ORDER =
            Comparator.comparing(Booking::getStartsAt).thenComparing(Booking::getId);

    private final BookingRepository bookingRepository;
    private final PhoneHasher phoneHasher;
    private final Clock clock;

    public GuestBookingDetailsText(BookingRepository bookingRepository, PhoneHasher phoneHasher, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.phoneHasher = phoneHasher;
        this.clock = clock;
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
                .map(GuestBookingDetailsText::format);
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

    static String format(List<Booking> items) {
        Booking first = items.get(0);
        OffsetDateTime kyiv = first.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        StringBuilder text = new StringBuilder(HEADING)
                .append(NEWLINE).append(items.size() == 1 ? SERVICE : SERVICES).append(serviceNames(items))
                .append(NEWLINE).append(DATE).append(DATE_FMT.format(kyiv)).append(AT).append(TIME_FMT.format(kyiv));
        appendLine(text, MASTER, masterName(first.getMaster()));
        Salon salon = first.getSalon();
        if (salon != null) {
            appendLine(text, SALON, salon.getName());
        }
        appendLine(text, ADDRESS, address(first));
        appendLine(text, PRICE, price(items));
        return text.toString();
    }

    private static String serviceNames(List<Booking> items) {
        return items.stream().map(GuestSmsServiceName::of).collect(Collectors.joining(LIST_SEPARATOR));
    }

    private static void appendLine(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(NEWLINE).append(label).append(value.strip());
        }
    }

    /** The booked salon's address wins outright (even when blank); an independent master's own otherwise. */
    private static String address(Booking first) {
        Salon salon = first.getSalon();
        User masterUser = first.getMaster().getUser();
        if (salon == null && masterUser == null) {
            return null;
        }
        String street = salon != null ? salon.getStreet() : masterUser.getStreet();
        String building = salon != null ? salon.getBuildingNo() : masterUser.getBuildingNo();
        return Stream.of(street, building)
                .filter(part -> part != null && !part.isBlank())
                .map(String::strip)
                .collect(Collectors.joining(LIST_SEPARATOR));
    }

    /** Sum of the frozen floors, and of the ceilings (floor when single-priced), as in the visit detail. */
    private static String price(List<Booking> items) {
        BigDecimal floor = BigDecimal.ZERO;
        BigDecimal ceiling = BigDecimal.ZERO;
        boolean anyRange = false;
        for (Booking item : items) {
            floor = floor.add(item.getPriceAtBooking());
            BigDecimal max = item.getPriceMaxAtBooking();
            anyRange |= max != null;
            ceiling = ceiling.add(max != null ? max : item.getPriceAtBooking());
        }
        return anyRange && ceiling.compareTo(floor) > 0
                ? PriceDisplayFormatter.format(PriceType.RANGE, floor, ceiling)
                : PriceDisplayFormatter.format(PriceType.FIXED, floor, null);
    }

    // V157 / phase 294 D3: displayFirstName()/displayLastName() — a detached master has no user row.
    private static String masterName(Master master) {
        String first = master.displayFirstName() == null ? "" : master.displayFirstName().trim();
        String last = master.displayLastName() == null ? "" : master.displayLastName().trim();
        return (first + " " + last).trim();
    }
}

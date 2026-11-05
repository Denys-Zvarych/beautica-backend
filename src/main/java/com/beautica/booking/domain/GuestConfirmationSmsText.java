package com.beautica.booking.domain;

import com.beautica.booking.entity.Booking;
import com.beautica.common.TimeZones;
import com.beautica.master.entity.Master;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Phase 435 follow-up — the ONE renderer of the guest «Ваш запис підтверджено!» text, shared by the LINK confirmation
 * SMS ({@code GuestBookingService}), the walk-in confirmation SMS ({@code StaffBookingService}) and the Telegram
 * details sent after linking ({@code GuestBookingDetailsText}), so the Telegram copy is the SMS copy by construction:
 *
 * <pre>
 * Beautica: Ваш запис підтверджено!
 *
 * {date} о {time}
 * Майстер {masterName}.                       (salon: «Салон {salonName}, майстер {masterName}.»)
 * Адреса: {address} ({locationNote})
 * Скасувати: {cancelUrl}
 * </pre>
 *
 * Every provider value goes through {@link GuestSmsProviderText} (salon / master name, address, note); the address
 * and note come from {@link GuestVisitAddress}. The «Адреса:» line is dropped without an address, the brackets without
 * a note, the «Скасувати:» line without a link (Telegram for a row created before V230). Whole text ≤
 * {@link GuestSmsProviderText#MAX_SMS_CHARS}: note, then address, then salon are dropped first. Never logged.
 */
public final class GuestConfirmationSmsText {

    public static final String SALON_NAME = "salonName";
    public static final String MASTER_NAME = "masterName";
    public static final String ADDRESS = "address";
    public static final String CANCEL_URL = "cancelUrl";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> OPTIONAL = Set.of(CANCEL_URL);
    private static final Set<String> ADDRESS_KEYS = Set.of(ADDRESS);

    private GuestConfirmationSmsText() {
    }

    /**
     * @param salonTemplate  the salon variant (used when the booking has a salon whose name survives sanitising)
     * @param masterTemplate the independent-master variant (also the fallback)
     * @param first          the booking, or a visit's first item (its start, master, salon and address are shown)
     * @param cancelUrl      the guest's own page, or {@code null} to drop the «Скасувати:» line
     */
    public static String render(String salonTemplate, String masterTemplate, Booking first, String cancelUrl) {
        OffsetDateTime kyiv = first.getStartsAt().atZoneSameInstant(TimeZones.KYIV).toOffsetDateTime();
        Map<String, String> values = new HashMap<>(GuestVisitAddress.smsValues(first, ADDRESS_KEYS));
        if (first.getSalon() != null) {
            GuestSmsProviderText.salonName(first.getSalon().getName()).ifPresent(name -> values.put(SALON_NAME, name));
        }
        // A URL-like master name is dropped — render then removes the master segment.
        GuestSmsProviderText.masterName(masterName(first.getMaster())).ifPresent(name -> values.put(MASTER_NAME, name));
        values.put("date", DATE_FMT.format(kyiv));
        values.put("time", TIME_FMT.format(kyiv));
        if (cancelUrl != null && !cancelUrl.isBlank()) {
            values.put(CANCEL_URL, cancelUrl);
        }
        return GuestSmsProviderText.render(first.getSalon() != null ? salonTemplate : null, masterTemplate,
                values, OPTIONAL, ADDRESS_KEYS);
    }

    // V157 / phase 294 D3: displayFirstName()/displayLastName() — a detached master has no user row.
    private static String masterName(Master master) {
        String firstName = master.displayFirstName();
        String lastName = master.displayLastName();
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        return (first + " " + last).trim();
    }
}

package com.beautica.moderation;

import com.beautica.booking.retention.GuestPii;
import com.beautica.common.exception.BookingUnavailableException;
import com.beautica.common.security.PhoneHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 398 D7 — the support guest-PII erase ({@code POST /internal/guest-pii/erase}) renames the phone's GUEST
 * blocks to «Гість» and keeps hash + last 4, so the block keeps refusing that phone; other blocks are untouched.
 */
@DisplayName("Guest block survives erase-by-phone — phase 398 IT")
class GuestBlockEraseByPhoneIT extends AbstractBookingBlockIT {

    private static final String PHONE = "+380671234577";
    private static final String OTHER_PHONE = "+380671234578";

    @Autowired
    private PhoneHasher phoneHasher;

    @Test
    @DisplayName("erase → the phone's guest block is named «Гість», hash + last 4 kept, booking still refused")
    void should_anonymiseNameAndKeepEnforcing_when_phoneErased() throws Exception {
        SalonFixture salon = createSalon();
        UUID service = makeSalonMasterBookable(salon.salonId(), salon.masterId());
        String salonSlug = setSalonSlug(salon.salonId());
        String ownerToken = tokenFor(salon.ownerEmail());
        UUID erased = block(ownerToken, insertGuestBooking(salon, "LINK", PHONE));
        UUID untouched = block(ownerToken, insertGuestBooking(salon, "LINK", OTHER_PHONE));

        ResponseEntity<String> erase = internal(HttpMethod.POST, "/api/v1/internal/guest-pii/erase",
                "{\"phone\":\"" + PHONE + "\",\"ticketRef\":\"SUP-398\"}", INTERNAL_KEY);

        assertThat(erase.getStatusCode()).as(erase.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = blockRow(erased);
        assertThat(row.get("display_name")).isEqualTo(GuestPii.ANONYMISED_NAME);
        assertThat((byte[]) row.get("phone_hash")).isEqualTo(phoneHasher.hash(PHONE).hash());
        assertThat(row.get("phone_last4")).isEqualTo("4577");
        assertThat(blockRow(untouched).get("display_name")).isEqualTo("Марія Ш.");
        assertUnavailable(guestBookSalon(guestTokenProvider.generate(PHONE), salonSlug, salon.masterId(),
                List.of(service), slot(3, 12)), BookingUnavailableException.SALON_MESSAGE);
    }

    private Map<String, Object> blockRow(UUID id) {
        return jdbcTemplate.queryForMap(
                "SELECT display_name, phone_hash, phone_last4 FROM client_blocks WHERE id = ?", id);
    }
}

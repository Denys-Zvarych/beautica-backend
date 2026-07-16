package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.dto.BookingDetailResponse;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.RecordComponent;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-authored (track 25.x / booking-enrichment-fields audit, 2026-07-14).
 *
 * <p><b>Why this test exists.</b> {@link BookingDetailResponse} is built by TWO independently
 * maintained mappers that must agree on every field for the same booking:
 * {@link BookingDetailResponse#from} (the entity path — backs {@code GET /bookings/{id}} and the
 * provider {@code GET /bookings/me} listing) and {@code BookingService#toDetailResponse} (the
 * CLIENT projection path — backs the CLIENT branch of {@code GET /bookings/me}, sourced from
 * {@code BookingRepository#findClientBookingDetails}). Nothing in the type system forces these
 * two mappers to compute a field the same way.
 *
 * <p>That gap shipped a HIGH-severity leak: the projection's JPQL used
 * {@code COALESCE(s.locationNote, mu.locationNote)} while the entity path used the null-safe
 * ternary {@code salon != null ? salon.getLocationNote() : masterUser.getLocationNote()}. For a
 * salon-employed master whose salon never filled in an address/note (the common case),
 * {@code COALESCE} fell through to the master's OWN personal {@code locationNote} (e.g. their
 * home door code) — so {@code GET /bookings/me} leaked it while {@code GET /bookings/{id}}
 * correctly returned {@code null} for the exact same booking. The suite stayed green at 838/838
 * because {@code ClientBookingDetailProjectionTest}'s salon case only ever exercised "salon note
 * IS set" — never the divergent "salon exists but its note is null" case.
 *
 * <p>{@code ClientBookingDetailProjectionTest} now pins the fix at the repository layer for
 * {@code street}/{@code buildingNo}/{@code locationNote}. THIS test closes the systemic gap: it
 * drives both real HTTP read paths for the SAME booking and, via reflection over
 * {@link BookingDetailResponse}'s record components, asserts EVERY field agrees — not just the
 * three fields this incident happened to touch. A future field added to the DTO with divergent
 * logic in only one of the two mappers fails this test automatically, with no one having to
 * remember to hand-write a new assertion for it.
 *
 * <p>The fixture reproduces the exact incident shape: the master's own user row carries a full
 * address, a personal {@code locationNote}, and a {@code professionalTitle}; the salon they work
 * for is "bare" — no city/district/street/buildingNo/locationNote set.
 */
@Import(TestSecurityConfig.class)
@DisplayName("BookingDetailResponse — entity-path vs CLIENT-projection-path field parity contract")
class BookingDetailContractIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("GET /bookings/{id} (entity path) and the matching row of GET /bookings/me "
            + "(CLIENT projection path) return IDENTICAL values for EVERY BookingDetailResponse "
            + "field, for a salon-employed master whose salon has no address/note set — the exact "
            + "shape that let COALESCE leak the master's personal locationNote (HIGH regression)")
    void should_matchEveryDtoField_when_salonEmployedMasterBookingFetchedViaBothPaths() throws Exception {
        Fixture fx = seedSalonBookingWithDivergentAddresses();
        UUID bookingId = insertConfirmedBooking(fx);
        String clientToken = tokenFor(fx.clientEmail());

        JsonNode single = getBookingDetail(bookingId, clientToken);
        JsonNode listItem = findInMyBookings(bookingId, clientToken);

        assertThat(listItem)
                .as("booking %s must appear on the client's own GET /bookings/me page", bookingId)
                .isNotNull();

        RecordComponent[] fields = BookingDetailResponse.class.getRecordComponents();
        assertThat(fields).as("sanity: the DTO must expose fields to compare").isNotEmpty();

        for (RecordComponent rc : fields) {
            String field = rc.getName();
            JsonNode fromEntityPath = single.get(field);
            JsonNode fromProjectionPath = listItem.get(field);
            assertThat(fromProjectionPath)
                    .as("field '%s' must agree between GET /bookings/{id} (entity path, value=%s) "
                            + "and GET /bookings/me (CLIENT projection path, value=%s) for the SAME "
                            + "booking %s — a divergence here means the two independently maintained "
                            + "mappers compute this field differently",
                            field, fromEntityPath, fromProjectionPath, bookingId)
                    .isEqualTo(fromEntityPath);
        }

        // Sanity: prove the fixture actually exercised the divergence-prone fields — the loop
        // above would pass vacuously if both sides happened to agree by both being wrong.
        assertThat(single.get("locationNote").isNull())
                .as("the salon has no note set — must be null, never the master's personal "
                        + "door-code note")
                .isTrue();
        assertThat(single.get("street").isNull())
                .as("the salon has no street set — must be null, never the master's own street")
                .isTrue();
        assertThat(single.get("buildingNo").isNull()).isTrue();
        assertThat(single.get("masterProfessionalTitle").asText())
                .as("professionalTitle always reads from the master's own user row — proves the "
                        + "fixture's master-side data actually reached the DTO on both paths")
                .isEqualTo("Майстер стрижки");
        assertThat(single.get("salonName").asText())
                .isEqualTo("Contract Bare Salon");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private record Fixture(UUID salonId, UUID masterId, UUID masterUserId, UUID masterServiceId,
                            String clientEmail, UUID clientId) {
    }

    private Fixture seedSalonBookingWithDivergentAddresses() {
        String ownerEmail = "contract-owner-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);

        // Bare salon: name only — NO city/district/street/buildingNo/locationNote. This is the
        // common real-world shape (most salons never fill in an address) and the exact one that
        // made COALESCE(s.X, mu.X) fall through to the master's own value.
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Contract Bare Salon");

        String masterEmail = "contract-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);

        UUID[] masterCityDistrict = resolveDistrictAndCity();
        // The master's OWN user row: full address + a personal note + a professional title —
        // none of this must ever surface on a booking under this salon.
        jdbcTemplate.update(
                "UPDATE users SET city_id = ?, district_id = ?, street = ?, building_no = ?, "
                        + "location_note = ?, professional_title = ? WHERE id = ?",
                masterCityDistrict[1], masterCityDistrict[0], "MasterOwnStreet", "13",
                "Master's home door code - must NOT surface on a salon booking",
                "Майстер стрижки", masterUserId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Haircut', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        String clientEmail = "contract-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createUser(clientEmail, "CLIENT", null);

        return new Fixture(salonId, masterId, masterUserId, masterServiceId, clientEmail, clientId);
    }

    private UUID insertConfirmedBooking(Fixture fx) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', NOW() + interval '1 day', "
                        + "NOW() + interval '1 day 1 hour', 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, fx.clientId(), fx.masterId(), fx.masterServiceId(), fx.salonId());
        return bookingId;
    }

    /** A real, occupied-territory-excluded (V53 seed) city/district pair — {@code [districtId, cityId]}. */
    private UUID[] resolveDistrictAndCity() {
        return jdbcTemplate.queryForObject(
                "SELECT id, city_id FROM city_districts ORDER BY id LIMIT 1",
                (rs, n) -> new UUID[]{(UUID) rs.getObject(1), (UUID) rs.getObject(2)});
    }

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private String tokenFor(String email) {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        try {
            return objectMapper.readValue(resp.getBody(),
                    new TypeReference<ApiResponse<AuthResponse>>() {}).data().accessToken();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse login response for " + email, e);
        }
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode getBookingDetail(UUID bookingId, String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).get("data");
    }

    private JsonNode findInMyBookings(UUID bookingId, String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/me?size=50", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = objectMapper.readTree(resp.getBody()).path("data").path("data");
        for (JsonNode item : items) {
            if (item.path("id").asText().equals(bookingId.toString())) {
                return item;
            }
        }
        return null;
    }
}

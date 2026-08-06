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
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
@DisplayName("BookingDetailResponse — entity-path vs CLIENT-projection-path field parity contract, "
        + "plus cross-master read denial on GET /bookings/{id}")
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

        // clientAvatarUrl non-vacuity. The reflective loop above ALREADY compares this field —
        // it enumerates getRecordComponents(), so the component was picked up the moment it was
        // added to the DTO, with no new hand-written assertion needed. But "both sides agree"
        // is worthless while both sides are null, which is exactly what the unseeded fixture
        // produced. This pins that the compared value is real on BOTH mapper paths: the entity
        // path (client.getAvatarUrl()) and the CLIENT projection path (b.client.avatarUrl).
        assertThat(single.get("clientAvatarUrl").asText())
                .as("entity path (GET /bookings/{id}) must serve the client's seeded avatar, so "
                        + "the parity loop compares a real value rather than null == null")
                .isEqualTo(CLIENT_AVATAR_URL);
        assertThat(listItem.get("clientAvatarUrl").asText())
                .as("CLIENT projection path (GET /bookings/me) must serve the same real value — "
                        + "this is the JPQL `b.client.avatarUrl` select, a physically different "
                        + "read from the entity path's getter walk")
                .isEqualTo(CLIENT_AVATAR_URL);
        assertThat(single.get("clientAvatarUrl").asText())
                .as("the client's avatar must never be the MASTER's — the two fields read two "
                        + "different User graphs and a swap would be invisible if both were null")
                .isNotEqualTo(MASTER_AVATAR_URL);
        assertThat(single.get("masterAvatarUrl").asText())
                .as("sibling field sanity — proves the master's avatar is genuinely a different "
                        + "seeded value, not absent, so the inequality above is meaningful")
                .isEqualTo(MASTER_AVATAR_URL);

        // masterAvgRating/masterReviewCount non-vacuity (Phase B1), same reasoning as the
        // clientAvatarUrl block above: the reflective loop already compares both fields, but the
        // column defaults (0.00 / 0) normalise to null on BOTH paths, so without a seeded rating
        // the comparison would be null == null. The entity path reads master.getAvgRating(); the
        // CLIENT projection path reads the JPQL `m.avgRating` select — physically different reads.
        assertThat(new BigDecimal(single.get("masterAvgRating").asText()))
                .as("entity path (GET /bookings/{id}) must serve the seeded master rating")
                .isEqualByComparingTo(MASTER_AVG_RATING);
        assertThat(new BigDecimal(listItem.get("masterAvgRating").asText()))
                .as("CLIENT projection path (GET /bookings/me) must serve the same real value")
                .isEqualByComparingTo(MASTER_AVG_RATING);
        assertThat(single.get("masterReviewCount").asInt()).isEqualTo(MASTER_REVIEW_COUNT);
        assertThat(listItem.get("masterReviewCount").asInt()).isEqualTo(MASTER_REVIEW_COUNT);

        // salonId non-vacuity (Phase B2). The reflective loop above already compares the field,
        // but it would compare null == null for an independent master's booking; this fixture
        // seeds bookings.salon_id explicitly (see insertConfirmedBooking). The two paths read it
        // physically differently — the entity path walks booking.getSalon().getId(), the CLIENT
        // projection path selects the `b.salon.id` FK in JPQL — so this also pins that the JPQL
        // identifier path resolves at all.
        assertThat(single.get("salonId").asText())
                .as("entity path (GET /bookings/{id}) must serve the booking's own salon_id")
                .isEqualTo(fx.salonId().toString());
        assertThat(listItem.get("salonId").asText())
                .as("CLIENT projection path (GET /bookings/me) must serve the same salon_id")
                .isEqualTo(fx.salonId().toString());
    }

    /**
     * Cross-master denial at the HTTP layer for {@code GET /bookings/{id}}.
     *
     * <p>The endpoint is guarded by {@code AuthorizationService#enforceCanViewBooking} — NOT by the
     * {@code canViewBooking} SpEL predicate that {@code AuthorizationServiceTest} historically
     * covered. The two are independently maintained twins, so this drives the real request path end
     * to end: a SALON_MASTER at the SAME salon as the booking's master (the hardest case — no
     * salon-level check can separate them; only fix M1's per-master id equality can) must be denied.
     *
     * <p>A status-only assertion would be insufficient here, and deliberately is not what this test
     * makes. The regression actually worth catching is a partial-DTO leak: the request 403s but a
     * serializer still emits the booking's third-party PII into the error envelope. So the body is
     * asserted twice over — no PII-bearing KEY anywhere in the JSON tree, and no seeded PII VALUE
     * anywhere in the raw response text. The legitimate client's 200 read is performed first so the
     * absence assertions cannot pass vacuously against a booking that never carried the data.
     */
    @Test
    @DisplayName("GET /bookings/{id} — 403 with a PII-free body when a SALON_MASTER fetches a "
            + "colleague's booking at the same salon (enforceCanViewBooking cross-master denial; "
            + "the response would otherwise carry a third party's name and arrival address)")
    void should_return403_when_masterFetchesAnotherMastersBookingDetail() throws Exception {
        Fixture fx = seedSalonBookingWithDivergentAddresses();
        // Give the booking's client a real name so "no PII in the 403 body" is a claim about
        // data that demonstrably exists, not about a fixture that never had any.
        jdbcTemplate.update("UPDATE users SET first_name = ?, last_name = ? WHERE id = ?",
                CLIENT_FIRST_NAME, CLIENT_LAST_NAME, fx.clientId());
        UUID bookingId = insertConfirmedBooking(fx);

        // Non-vacuity gate: the legitimate owner's read really does surface the client's name.
        JsonNode legitimate = getBookingDetail(bookingId, tokenFor(fx.clientEmail()));
        assertThat(legitimate.get("clientFirstName").asText())
                .as("fixture sanity — the booking must actually carry client PII for the absence "
                        + "assertions below to mean anything")
                .isEqualTo(CLIENT_FIRST_NAME);
        assertThat(legitimate.get("clientAvatarUrl").asText())
                .as("same non-vacuity gate for the client's LIKENESS: the denial assertions below "
                        + "only prove something if an authorized read demonstrably DOES serve this "
                        + "URL for this booking")
                .isEqualTo(CLIENT_AVATAR_URL);

        String foreignMasterEmail = seedForeignMasterAtSameSalon(fx.salonId());

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenFor(foreignMasterEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("a SALON_MASTER must not read a colleague's booking — expected 403, body=%s",
                        resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);

        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.path("data").isNull() || body.path("data").isMissingNode())
                .as("the 403 envelope must carry no booking payload at all, data=%s",
                        body.path("data"))
                .isTrue();

        assertThat(collectFieldNames(body))
                .as("no PII-bearing key may appear anywhere in the 403 response tree — a partial "
                        + "DTO serialised into an error envelope leaks a third party's data even "
                        + "though the status code is correct; body=%s", resp.getBody())
                .doesNotContainAnyElementsOf(PII_FIELD_NAMES);

        assertThat(resp.getBody())
                .as("no seeded PII VALUE may appear anywhere in the raw 403 body — catches a leak "
                        + "smuggled through a renamed or nested field the key scan would miss")
                .doesNotContain(CLIENT_FIRST_NAME)
                .doesNotContain(CLIENT_LAST_NAME)
                // The client's LIKENESS. An avatar URL is a directly dereferenceable, publicly
                // readable R2 object — leaking it into a 403 envelope hands a denied actor the
                // client's photo outright, which is strictly worse than leaking an opaque id.
                .doesNotContain(CLIENT_AVATAR_URL)
                .doesNotContain("Contract Bare Salon")
                .doesNotContain("MasterOwnStreet")
                .doesNotContain("Майстер стрижки");
    }

    /**
     * The POSITIVE half of the {@code clientAvatarUrl} widening — the feature itself.
     *
     * <p>Everything else about this field is asserted from the CLIENT's own two read paths, where
     * the value is simply the caller's own photo and no widening has occurred. The widening is
     * this: a PROVIDER receives a photo of somebody else. That path is
     * {@code listProviderBookings} → {@code findIdsByMasterIdFiltered} + {@code
     * findAllByIdsWithGraph} → {@code BookingDetailResponse.from} — a physically different query
     * and a different scoping branch from either client path, and it had no assertion on this
     * field anywhere in {@code src/test/}.
     *
     * <p>Asserted against DISTINCT seeded avatars so this cannot pass on a mapper that serves the
     * master their own picture: the provider must receive the CLIENT's URL, and the row's
     * {@code masterAvatarUrl} must still independently carry the master's.
     */
    @Test
    @DisplayName("GET /bookings/me (provider path) — a master's own booking row carries the "
            + "BOOKING CLIENT's avatar URL, not the master's own; this is the widening the field "
            + "exists for and the provider-scoped query had no assertion on it")
    void should_serveClientAvatarToProvider_when_masterListsOwnBookings() throws Exception {
        Fixture fx = seedSalonBookingWithDivergentAddresses();
        UUID bookingId = insertConfirmedBooking(fx);

        String masterEmail = jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, fx.masterUserId());
        JsonNode providerRow = findInMyBookings(bookingId, tokenFor(masterEmail));

        assertThat(providerRow)
                .as("the booking's own master must see it on their provider timeline")
                .isNotNull();
        assertThat(providerRow.get("clientAvatarUrl").asText())
                .as("the provider must receive the CLIENT's photo — this is the whole point of "
                        + "the field, and the entity-graph provider query had zero coverage of it")
                .isEqualTo(CLIENT_AVATAR_URL);
        assertThat(providerRow.get("clientAvatarUrl").asText())
                .as("a mapper that read masterUser.getAvatarUrl() into this slot would render the "
                        + "master's own face on every client card — distinct seeded URLs are what "
                        + "make that swap fail here instead of shipping")
                .isNotEqualTo(MASTER_AVATAR_URL);
        assertThat(providerRow.get("masterAvatarUrl").asText())
                .as("sibling field must still independently carry the master's own avatar")
                .isEqualTo(MASTER_AVATAR_URL);
    }

    /**
     * Phase 242 — the address block follows the BOOKING's salon, end-to-end, on BOTH mapper paths.
     *
     * <p>The leak this closes: {@code salonName}/{@code street}/{@code buildingNo}/
     * {@code locationNote}/{@code cityLabel}/{@code districtLabel} used to resolve off
     * {@code master.getSalon()} — the master's LIVE affiliation — so once a master rotated salons,
     * a client opening an OLD booking was served the NEW salon's {@code locationNote}. That field
     * is by its own {@code @Schema} contract the provider's arrival hint and holds door codes
     * («3-й поверх, код 1234»), i.e. premises-access information for a salon the client has never
     * booked at.
     *
     * <p>Both salons carry DISTINCT, NON-NULL street / buildingNo / locationNote / city / district.
     * A null on either side would make every {@code isNotEqualTo} below pass vacuously — the trap
     * this suite's own history is full of. The premise assertions guard exactly that.
     *
     * <p>Runs against the client's TWO read paths, which are physically different queries and
     * independently maintained mappers: {@code GET /bookings/{id}} (entity path,
     * {@code findByIdWithFullGraph} → {@code BookingDetailResponse#from}) and
     * {@code GET /bookings/me} (projection path, {@code hydrateClientBookingDetails}). A fix
     * applied to only one of them is the exact divergence class the reflective parity loop above
     * exists for.
     */
    @Test
    @DisplayName("after the master rotates salons, BOTH client read paths still serve the BOOKED "
            + "salon's address and door code — the new salon's note never reaches the client")
    void should_serveTheBookedSalonsAddress_when_theMasterHasSinceRotatedToAnotherSalon() throws Exception {
        Fixture fx = seedSalonBookingWithDivergentAddresses();
        UUID bookingId = insertConfirmedBooking(fx);

        Locality localityA = resolveLocality(0);
        Locality localityB = resolveLocality(1);
        assertThat(localityB.cityLabel())
                .as("premise — the two seeded localities must differ, or the label assertions "
                        + "below compare a value against itself and prove nothing")
                .isNotEqualTo(localityA.cityLabel());

        // Salon A — where the visit was booked. bookings.salon_id already points here.
        String salonAStreet = "вул. Заброньована-A";
        String salonABuildingNo = "11-A";
        String salonANote = "A: 3-й поверх, код 1234";
        jdbcTemplate.update(
                "UPDATE salons SET name = ?, city_id = ?, district_id = ?, street = ?, "
                        + "building_no = ?, location_note = ? WHERE id = ?",
                "Booked Salon A", localityA.cityId(), localityA.districtId(),
                salonAStreet, salonABuildingNo, salonANote, fx.salonId());

        // Salon B — a second salon of the SAME owner (rotateMasterSalon only permits same-owner
        // moves), with a wholly different address and door code.
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, fx.salonId());
        String salonBStreet = "вул. Поточна-B";
        String salonBBuildingNo = "22-B";
        String salonBNote = "B: 5-й поверх, код 9999";
        UUID salonBId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city_id, district_id, street, building_no, "
                        + "location_note, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, NOW(), NOW())",
                salonBId, ownerId, "Current Salon B", localityB.cityId(), localityB.districtId(),
                salonBStreet, salonBBuildingNo, salonBNote);

        // The rotation itself — AFTER the booking exists. masters.salon_id moves;
        // bookings.salon_id is a snapshot and does not.
        jdbcTemplate.update("UPDATE masters SET salon_id = ? WHERE id = ?", salonBId, fx.masterId());
        jdbcTemplate.update("UPDATE users SET salon_id = ? WHERE id = ?", salonBId, fx.masterUserId());

        String clientToken = tokenFor(fx.clientEmail());
        String masterEmail = jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, fx.masterUserId());
        JsonNode detail = getBookingDetail(bookingId, clientToken);
        JsonNode listRow = findInMyBookings(bookingId, clientToken);
        // The provider list is a THIRD physical path: listProviderBookings + findAllByIdsWithGraph
        // + BookingService#discoveryCityId/discoveryDistrictId (its own locality resolution, NOT
        // enrichSingle's and NOT the projection's). Without it, re-pointing only two of the three
        // would still pass.
        JsonNode providerRow = findInMyBookings(bookingId, tokenFor(masterEmail));

        assertThat(listRow).as("the client's own booking must appear on GET /bookings/me").isNotNull();
        assertThat(providerRow)
                .as("the booking's own master must see it on their provider timeline").isNotNull();

        for (var path : List.of(
                Map.entry("GET /bookings/{id} (entity path)", detail),
                Map.entry("GET /bookings/me (projection path)", listRow),
                Map.entry("GET /bookings/me (provider entity path)", providerRow))) {
            String where = path.getKey();
            JsonNode row = path.getValue();

            assertThat(row.get("salonId").asText())
                    .as("%s — salonId is the booking's own snapshot", where)
                    .isEqualTo(fx.salonId().toString())
                    .isNotEqualTo(salonBId.toString());
            assertThat(row.get("salonName").asText())
                    .as("%s — salonName now shares salonId's source; before phase 242 it tracked "
                            + "the master's LIVE salon and the two disagreed here", where)
                    .isEqualTo("Booked Salon A")
                    .isNotEqualTo("Current Salon B");
            assertThat(row.get("street").asText())
                    .as("%s — street", where).isEqualTo(salonAStreet).isNotEqualTo(salonBStreet);
            assertThat(row.get("buildingNo").asText())
                    .as("%s — buildingNo", where)
                    .isEqualTo(salonABuildingNo).isNotEqualTo(salonBBuildingNo);
            assertThat(row.get("cityLabel").asText())
                    .as("%s — cityLabel must describe the same premises as street, or the client "
                            + "is sent to salon A's street in salon B's city. Each of the three "
                            + "paths resolves this through a DIFFERENT piece of code "
                            + "(enrichSingle / the projection's CASE WHEN / discoveryCityId), so "
                            + "all three have to be re-pointed together.", where)
                    .isEqualTo(localityA.cityLabel()).isNotEqualTo(localityB.cityLabel());
            // THE security assertion, with the negative stated explicitly: a positive-only check
            // would still pass if both salons happened to carry the same note.
            assertThat(row.get("locationNote").asText())
                    .as("%s — the client booked at salon A, so they must receive A's door code and "
                            + "NEVER B's premises-access information", where)
                    .isEqualTo(salonANote)
                    .isNotEqualTo(salonBNote);
        }

        // Nothing about salon B may be smuggled into any other field of either body.
        for (JsonNode row : List.of(detail, listRow)) {
            assertThat(row.toString())
                    .as("no field anywhere in the response may carry the rotated-to salon's "
                            + "address or door code")
                    .doesNotContain(salonBNote)
                    .doesNotContain(salonBStreet)
                    .doesNotContain(salonBBuildingNo)
                    .doesNotContain("Current Salon B")
                    .doesNotContain("Master's home door code");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static final String CLIENT_FIRST_NAME = "Оксана";
    private static final String CLIENT_LAST_NAME = "Кравченко";

    /**
     * Distinct, non-null avatar URLs for the booking's client and its master.
     *
     * <p>They MUST differ. {@code clientAvatarUrl} and {@code masterAvatarUrl} are adjacent String
     * reads off two different {@code User} graphs, and every fixture in this suite previously left
     * both columns NULL — so a mapper that read the master's avatar into the client's field would
     * have been invisible (null == null) to the reflective parity loop AND to the raw-body PII
     * scan. Two different non-null values is what gives both gates something to fail on.
     */
    private static final String CLIENT_AVATAR_URL =
            "https://cdn.beautica.test/avatars/contract-client-likeness.jpg";
    private static final String MASTER_AVATAR_URL =
            "https://cdn.beautica.test/avatars/contract-master-likeness.jpg";

    /**
     * Phase B1 — the seeded master's rating aggregate. Non-default on purpose (the columns default
     * to {@code 0.00}/{@code 0}, which both mapper paths normalise to {@code null}/{@code 0}), so
     * the reflective parity loop compares a REAL value on both sides instead of {@code null ==
     * null}. The average is deliberately not a round number so a mapper that fabricated one would
     * not coincidentally match.
     */
    private static final BigDecimal MASTER_AVG_RATING = new BigDecimal("4.75");
    private static final int MASTER_REVIEW_COUNT = 12;

    /**
     * Keys that must never appear in a denied booking read. {@code guestName}/{@code guestSurname}
     * are not {@link BookingDetailResponse} components today — they fold into
     * {@code clientFirstName}/{@code clientLastName} for guest (LINK) bookings — and are listed
     * anyway so a future DTO that starts surfacing them cannot slip through this gate unnoticed.
     */
    private static final Set<String> PII_FIELD_NAMES = Set.of(
            "clientFirstName", "clientLastName", "clientId", "clientAvatarUrl",
            "guestName", "guestSurname", "guestPhone",
            "street", "buildingNo", "locationNote", "salonName",
            "cityLabel", "districtLabel",
            "clientComment", "clientCancellationNote", "providerComment");

    /** Every field name in the JSON tree, at any depth. */
    private static Set<String> collectFieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        collectFieldNames(node, names);
        return names;
    }

    private static void collectFieldNames(JsonNode node, Set<String> sink) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                sink.add(e.getKey());
                collectFieldNames(e.getValue(), sink);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectFieldNames(child, sink));
        }
    }

    /**
     * A second, genuinely distinct SALON_MASTER employed by the SAME salon as the booking's
     * master. Same-salon is the sharp case: any salon-scoped authorization check admits them, so
     * only the per-master id equality in {@code enforceCanViewBooking} can deny them.
     */
    private String seedForeignMasterAtSameSalon(UUID salonId) {
        String email = "contract-foreign-master-" + System.nanoTime() + "@beautica.test";
        UUID userId = createUser(email, "SALON_MASTER", salonId);
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                UUID.randomUUID(), userId, salonId);
        return email;
    }

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
                        + "location_note = ?, professional_title = ?, avatar_url = ? WHERE id = ?",
                masterCityDistrict[1], masterCityDistrict[0], "MasterOwnStreet", "13",
                "Master's home door code - must NOT surface on a salon booking",
                "Майстер стрижки", MASTER_AVATAR_URL, masterUserId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                // Phase B1: avg_rating/review_count are seeded to a REVIEWED master on purpose.
                // The reflective parity loop picks up masterAvgRating/masterReviewCount
                // automatically, but "both sides agree" is worthless while both sides are the
                // column defaults (0.00 / 0, which the mapper normalises to null / 0 on BOTH
                // paths) — that would compare null == null and pass vacuously.
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, "
                        + "avg_rating, review_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, ?, ?, NOW(), NOW())",
                masterId, masterUserId, salonId, MASTER_AVG_RATING, MASTER_REVIEW_COUNT);

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
        // The client's LIKENESS — the payload of the clientAvatarUrl widening. Seeded here (not
        // per-test) so EVERY test in this class, including the reflective parity loop, exercises
        // a non-null value rather than agreeing vacuously on null.
        jdbcTemplate.update("UPDATE users SET avatar_url = ? WHERE id = ?",
                CLIENT_AVATAR_URL, clientId);

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

    /**
     * A real seeded district + its city, together with the Ukrainian labels the
     * {@code DiscoveryLocationResolver} will resolve them to.
     *
     * @param index 0-based; distinct indices yield localities in DIFFERENT cities, so
     *              {@code cityLabel} genuinely differs between them (the premise the phase-242
     *              rotation test asserts before relying on it).
     */
    private Locality resolveLocality(int index) {
        return jdbcTemplate.queryForObject(
                "SELECT DISTINCT ON (c.id) d.id, c.id, c.name_uk, d.name_uk "
                        + "FROM city_districts d JOIN cities c ON c.id = d.city_id "
                        + "ORDER BY c.id, d.id OFFSET ? LIMIT 1",
                (rs, n) -> new Locality((UUID) rs.getObject(1), (UUID) rs.getObject(2),
                        rs.getString(3), rs.getString(4)),
                index);
    }

    private record Locality(UUID districtId, UUID cityId, String cityLabel, String districtLabel) {
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

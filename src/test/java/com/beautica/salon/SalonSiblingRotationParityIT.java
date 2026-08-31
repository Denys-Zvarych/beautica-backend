package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.dto.RotateAdminRequest;
import com.beautica.salon.dto.SiblingSalonOption;
import com.beautica.salon.repository.SalonRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 21.3b — <b>predicate-parity</b> tests binding
 * {@code GET /api/v1/salons/{salonId}/sibling-salons} to
 * {@code PATCH /api/v1/salons/{salonId}/admins/{userId}/salon}.
 *
 * <p>{@code SalonRepository#findActiveSiblingsBySalonId} documents that its predicate
 * ({@code isActive AND sameOwner AND id <> :salonId}) is the exact complement of what
 * {@code SalonService.rotateAdmin} accepts ({@code salonsShareOwner AND
 * existsByIdAndIsActiveTrue AND destinationSalonId != salonId}) and that "the two must not
 * drift" — but until this class that was a <em>comment</em>, not a test. Nothing stopped a
 * future edit to either predicate from making the picker offer a destination the mutation
 * rejects (an option guaranteed to fail in the mobile UI) or from hiding a legal one.
 *
 * <p>The pin is executed, not asserted-by-inspection:
 * <ul>
 *   <li><b>Every listed sibling is accepted</b> — the admin is rotated through the entire
 *       returned list, hop by hop, and every hop must return 200 and actually move
 *       {@code users.salon_id}. A row the picker offers but the mutation rejects fails here.</li>
 *   <li><b>Every omitted salon is rejected</b> — the three salons deliberately absent from the
 *       list (self, an inactive sibling, a foreign owner's salon) are each attempted as a
 *       destination and must be refused, with the admin left untouched. A row the mutation
 *       would accept but the picker hides fails here.</li>
 * </ul>
 *
 * <p>Both directions guard against vacuity: the listed-siblings test asserts the exact expected
 * id set <em>before</em> looping, so an empty or wrong list cannot make the loop trivially pass.
 */
@Import(TestSecurityConfig.class)
@DisplayName("sibling-salons ↔ rotate-admin — predicate parity (Phase 21.3b)")
class SalonSiblingRotationParityIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SalonSiblingRotationParityIT.class);

    private static final String SIBLINGS_URL = "/api/v1/salons/%s/sibling-salons";
    private static final String ROTATE_ADMIN_URL = "/api/v1/salons/%s/admins/%s/salon";
    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SalonRepository salonRepository;

    private SalonItFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new SalonItFixtures(
                restTemplate, jdbcTemplate, objectMapper, passwordEncoder, this::testCityId);
    }

    @Test
    @DisplayName("every salon the picker lists is a LEGAL rotate-admin destination — 200 on each hop")
    void should_acceptEveryListedSiblingAsRotationDestination_when_rotatingThroughTheWholePicker()
            throws Exception {
        // Arrange — a portfolio wide enough that the picker must actually filter: three active
        // siblings alongside the source itself, an inactive sibling, and a foreign owner's salon.
        UUID ownerId = fixtures.insertUser("owner-parity-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID sourceSalonId = fixtures.insertSalon(ownerId, "Parity Source Salon");
        UUID siblingOneId = fixtures.insertSalon(ownerId, "Parity Sibling One");
        UUID siblingTwoId = fixtures.insertSalon(ownerId, "Parity Sibling Two");
        UUID siblingThreeId = fixtures.insertSalon(ownerId, "Parity Sibling Three");
        fixtures.insertSalon(ownerId, "Parity Inactive Sibling", false);
        UUID foreignOwnerId = fixtures.insertUser("owner-parity-foreign-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        fixtures.insertSalon(foreignOwnerId, "Parity Foreign Salon");
        UUID adminId = fixtures.insertAdmin("admin-parity-" + System.nanoTime() + "@beautica.test", sourceSalonId);
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act — read the picker exactly as the mobile rotate-admin screen does
        log.debug("Act: GET {} then rotate the admin into EVERY id it returned, hop by hop",
                String.format(SIBLINGS_URL, sourceSalonId));
        List<SiblingSalonOption> siblings = dataOf(get(sourceSalonId, ownerToken));

        // Assert — pin the list FIRST so an empty/wrong picker cannot make the loop below vacuous
        assertThat(siblings)
                .as("the picker must offer exactly the three active same-owner siblings before "
                        + "the rotation loop can prove anything about them")
                .extracting(SiblingSalonOption::id)
                .containsExactlyInAnyOrder(siblingOneId, siblingTwoId, siblingThreeId);

        // Assert — each listed destination must be ACCEPTED by rotateAdmin. The admin walks the
        // list: after hop N its current salon is sibling N, which becomes hop N+1's path salon.
        UUID currentSalonId = sourceSalonId;
        for (SiblingSalonOption sibling : siblings) {
            ResponseEntity<String> rotation = rotate(currentSalonId, adminId, sibling.id(), ownerToken);
            assertThat(rotation.getStatusCode())
                    .as("sibling-salons offered salon %s as a rotation destination, so rotateAdmin "
                            + "MUST accept it — a 4xx here means the two predicates have drifted",
                            sibling.id())
                    .isEqualTo(HttpStatus.OK);
            assertThat(fixtures.readSalonId(adminId))
                    .as("rotation into offered salon %s must actually move users.salon_id", sibling.id())
                    .isEqualTo(sibling.id());
            currentSalonId = sibling.id();
        }
    }

    @Test
    @DisplayName("every salon the picker OMITS is refused by rotate-admin — self 400, inactive/foreign 403")
    void should_rejectEveryOmittedSalonAsRotationDestination_when_rotatingIntoAnUnlistedSalon()
            throws Exception {
        // Arrange — the complement half of the parity contract. One legitimate sibling is present
        // so the picker is provably filtering rather than returning nothing at all.
        UUID ownerId = fixtures.insertUser("owner-parity-neg-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID sourceSalonId = fixtures.insertSalon(ownerId, "Parity Neg Source Salon");
        UUID listedSiblingId = fixtures.insertSalon(ownerId, "Parity Neg Listed Sibling");
        UUID inactiveSiblingId = fixtures.insertSalon(ownerId, "Parity Neg Inactive Sibling", false);
        UUID foreignOwnerId = fixtures.insertUser("owner-parity-neg-foreign-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID foreignSalonId = fixtures.insertSalon(foreignOwnerId, "Parity Neg Foreign Salon");
        UUID adminId = fixtures.insertAdmin("admin-parity-neg-" + System.nanoTime() + "@beautica.test", sourceSalonId);
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // Act
        log.debug("Act: GET {} then attempt a rotation into each of the three salons it withheld",
                String.format(SIBLINGS_URL, sourceSalonId));
        List<SiblingSalonOption> siblings = dataOf(get(sourceSalonId, ownerToken));

        // Assert — the three withheld salons really are withheld
        assertThat(siblings)
                .as("self, the inactive sibling and the foreign salon must all be absent, while the "
                        + "one legal sibling is present")
                .extracting(SiblingSalonOption::id)
                .containsExactly(listedSiblingId)
                .doesNotContain(sourceSalonId, inactiveSiblingId, foreignSalonId);

        // Assert — self is the no-op 400 the picker exists to avoid rendering
        assertThat(rotate(sourceSalonId, adminId, sourceSalonId, ownerToken).getStatusCode())
                .as("the source salon is withheld because rotateAdmin rejects a same-salon no-op")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // Assert — inactive and foreign both collapse to the same 403 (no destination-status oracle)
        assertThat(rotate(sourceSalonId, adminId, inactiveSiblingId, ownerToken).getStatusCode())
                .as("the inactive sibling is withheld because rotateAdmin denies an inactive destination")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rotate(sourceSalonId, adminId, foreignSalonId, ownerToken).getStatusCode())
                .as("the foreign salon is withheld because rotateAdmin denies a cross-owner destination")
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(fixtures.readSalonId(adminId))
                .as("no withheld destination may have moved the admin")
                .isEqualTo(sourceSalonId);
    }

    @Test
    @DisplayName("the sibling query returns an empty list for a salonId with no salon row, never an error")
    void should_returnEmptyList_when_salonIdResolvesToNoSalonRow() {
        // Arrange — the repository Javadoc claims an unresolvable salonId yields an empty list via
        // the owner sub-select producing no row (rather than an NPE or a NonUniqueResult). The HTTP
        // gate denies that case with 403 first, so only a direct repository call can prove it, and
        // a real active salon is present so an empty result is a filtered one, not an empty table.
        UUID ownerId = fixtures.insertUser("owner-parity-ghost-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        fixtures.insertSalon(ownerId, "Parity Ghost Bystander Salon");
        UUID unknownSalonId = UUID.randomUUID();

        // Act
        log.debug("Act: query siblings for a salonId that was never inserted, with one active salon present");
        List<SiblingSalonOption> siblings =
                salonRepository.findActiveSiblingsBySalonId(unknownSalonId);

        // Assert
        assertThat(siblings)
                .as("an unresolvable salonId must degrade to an empty result, never leak an unrelated "
                        + "owner's active salon and never throw")
                .isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private ResponseEntity<String> get(UUID salonId, String token) {
        return restTemplate.exchange(
                String.format(SIBLINGS_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    private ResponseEntity<String> rotate(UUID salonId, UUID adminId, UUID destinationSalonId, String token) {
        return restTemplate.exchange(
                String.format(ROTATE_ADMIN_URL, salonId, adminId), HttpMethod.PATCH,
                new HttpEntity<>(new RotateAdminRequest(destinationSalonId), fixtures.bearerHeaders(token)),
                String.class);
    }

    private List<SiblingSalonOption> dataOf(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<List<SiblingSalonOption>>>() {}).data();
    }

}

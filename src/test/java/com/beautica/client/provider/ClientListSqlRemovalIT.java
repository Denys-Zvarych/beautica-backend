package com.beautica.client.provider;

import com.beautica.AbstractIntegrationTest;
import com.beautica.client.provider.ProviderClientFixtures.Salon;
import com.beautica.client.provider.repository.ClientListRowProjection;
import com.beautica.client.provider.repository.ProviderClientRepository;
import com.beautica.common.security.PhoneHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.beautica.client.provider.ProviderClientFixtures.freshPhone;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B564 — the {@code ClientListSql} removal filter at the statement level (no HTTP): removal rows and
 * {@code bookings.created_at} are seeded with exact instants so the CLI-83 boundary ({@code created_at > removed_at},
 * strictly) and the D1 scope keys are pinned without relying on wall-clock ordering.
 */
@DisplayName("ClientListSql — B564 removal filter")
class ClientListSqlRemovalIT extends AbstractIntegrationTest {

    private static final Instant REMOVED_AT = Instant.parse("2026-11-20T10:00:00Z");

    @Autowired private ProviderClientRepository repository;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PhoneHasher phoneHasher;

    private ProviderClientFixtures fx;

    @BeforeEach
    void setUpFixtures() {
        fx = new ProviderClientFixtures(jdbcTemplate, restTemplate, objectMapper, phoneHasher, this::testCityId,
                this::resolveUnusedServiceTypeId);
    }

    private UUID bookingCreatedAt(Salon s, UUID clientId, String status, Instant createdAt) {
        UUID id = fx.booking(s.masterId(), s.salonId(), clientId, status, fx.at(-5));
        jdbcTemplate.update("UPDATE bookings SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), id);
        return id;
    }

    private void removeInSalon(UUID salonId, UUID userId, String phone) {
        jdbcTemplate.update("INSERT INTO client_list_removals (id, salon_id, client_user_id, guest_phone, removed_at) "
                + "VALUES (?, ?, ?, ?, ?)", UUID.randomUUID(), salonId, userId, phone, Timestamp.from(REMOVED_AT));
    }

    private void removeInMaster(UUID masterId, UUID userId) {
        jdbcTemplate.update("INSERT INTO client_list_removals (id, master_id, client_user_id, removed_at) "
                + "VALUES (?, ?, ?, ?)", UUID.randomUUID(), masterId, userId, Timestamp.from(REMOVED_AT));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static List<UUID> clientIds(List<ClientListRowProjection> page) {
        return page.stream().map(ClientListRowProjection::getClientId).filter(Objects::nonNull).toList();
    }

    @Test
    @DisplayName("CLI-83 boundary: created_at == removed_at stays hidden; one microsecond later is shown")
    void should_applyStrictlyGreaterBoundary_when_bookingCreatedAtRemovalInstant() {
        Salon s = fx.salon();
        UUID equal = fx.client("Рівна", "Межа");
        UUID later = fx.client("Пізніша", "Межа");
        bookingCreatedAt(s, equal, "COMPLETED", REMOVED_AT);
        bookingCreatedAt(s, later, "COMPLETED", REMOVED_AT.plus(1, ChronoUnit.MICROS));
        removeInSalon(s.salonId(), equal, null);
        removeInSalon(s.salonId(), later, null);

        List<ClientListRowProjection> page = repository.pageBySalon(s.salonId(), now(), null, null, 20, 0);

        assertThat(clientIds(page)).containsExactly(later);
        assertThat(page.get(0).getTotal()).isEqualTo(1L);
    }

    @Test
    @DisplayName("CLI-84: the returned row's aggregates count the bookings from before the removal too")
    void should_aggregateAllBookings_when_returnedAfterRemoval() {
        Salon s = fx.salon();
        UUID anna = fx.client("Анна", "Коваль");
        bookingCreatedAt(s, anna, "COMPLETED", REMOVED_AT.minus(30, ChronoUnit.DAYS));
        bookingCreatedAt(s, anna, "COMPLETED", REMOVED_AT.minus(10, ChronoUnit.DAYS));
        bookingCreatedAt(s, anna, "CANCELLED", REMOVED_AT.plus(1, ChronoUnit.HOURS));
        removeInSalon(s.salonId(), anna, null);

        List<ClientListRowProjection> page = repository.pageBySalon(s.salonId(), now(), null, null, 20, 0);

        assertThat(clientIds(page)).containsExactly(anna);
        assertThat(page.get(0).getCompletedVisits()).isEqualTo(2L);
    }

    @Test
    @DisplayName("D1: a salon removal hides the salon and salon+master pages only; the master page is unaffected")
    void should_matchSalonRowOnlyOnSalonPages_when_salonRemoval() {
        Salon s = fx.salon();
        UUID anna = fx.client("Анна", "Коваль");
        bookingCreatedAt(s, anna, "COMPLETED", REMOVED_AT.minus(1, ChronoUnit.DAYS));
        removeInSalon(s.salonId(), anna, null);

        List<ClientListRowProjection> salon = repository.pageBySalon(s.salonId(), now(), null, null, 20, 0);
        List<ClientListRowProjection> salonMaster = repository.pageBySalonAndMaster(s.salonId(), s.masterId(), now(),
                null, null, 20, 0);
        List<ClientListRowProjection> master = repository.pageByMaster(s.masterId(), now(), null, null, 20, 0);

        assertThat(clientIds(salon)).isEmpty();
        assertThat(salon).singleElement().satisfies(p -> {
            assertThat(p.getAnchorBookingId()).as("empty-page placeholder").isNull();
            assertThat(p.getTotal()).isZero();
        });
        assertThat(clientIds(salonMaster)).isEmpty();
        assertThat(clientIds(master)).containsExactly(anna);
    }

    @Test
    @DisplayName("D1: a master removal hides the master page only; salon pages (± masterId) are unaffected")
    void should_matchMasterRowOnlyOnMasterPage_when_masterRemoval() {
        Salon s = fx.salon();
        UUID anna = fx.client("Анна", "Коваль");
        bookingCreatedAt(s, anna, "COMPLETED", REMOVED_AT.minus(1, ChronoUnit.DAYS));
        removeInMaster(s.masterId(), anna);

        assertThat(clientIds(repository.pageByMaster(s.masterId(), now(), null, null, 20, 0))).isEmpty();
        assertThat(clientIds(repository.pageBySalon(s.salonId(), now(), null, null, 20, 0))).containsExactly(anna);
        assertThat(clientIds(repository.pageBySalonAndMaster(s.salonId(), s.masterId(), now(), null, null, 20, 0)))
                .containsExactly(anna);
    }

    @Test
    @DisplayName("another salon's removal of the same client and a guest removal by another phone match nothing here")
    void should_ignoreForeignScopeAndOtherIdentity_when_listing() {
        Salon s = fx.salon();
        Salon other = fx.salon();
        UUID anna = fx.client("Анна", "Коваль");
        bookingCreatedAt(s, anna, "COMPLETED", REMOVED_AT.minus(1, ChronoUnit.DAYS));
        String phone = freshPhone("50");
        UUID guest = fx.guestBooking(s.masterId(), s.salonId(), phone, "Іванна", "Гість", "COMPLETED", fx.at(-4));
        jdbcTemplate.update("UPDATE bookings SET created_at = ? WHERE id = ?",
                Timestamp.from(REMOVED_AT.minus(1, ChronoUnit.DAYS)), guest);
        removeInSalon(other.salonId(), anna, null);
        removeInSalon(other.salonId(), null, phone);
        removeInSalon(s.salonId(), null, freshPhone("67"));

        List<ClientListRowProjection> page = repository.pageBySalon(s.salonId(), now(), null, null, 20, 0);

        assertThat(page).hasSize(2);
        assertThat(page.get(0).getTotal()).isEqualTo(2L);

        removeInSalon(s.salonId(), null, phone);
        List<ClientListRowProjection> after = repository.pageBySalon(s.salonId(), now(), null, null, 20, 0);
        assertThat(clientIds(after)).containsExactly(anna);
        assertThat(after).singleElement().satisfies(p -> assertThat(p.getGuestPhone()).isNull());
    }
}

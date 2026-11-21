package com.beautica.client.provider;

import com.beautica.booking.enums.BookingStatus;
import com.beautica.client.provider.dto.ClientCardResponse;
import com.beautica.client.provider.dto.ClientKind;
import com.beautica.client.provider.dto.ClientVisitResponse;
import com.beautica.client.provider.repository.ClientCardRowProjection;
import com.beautica.client.provider.repository.ClientVisitItemProjection;
import com.beautica.client.provider.repository.ClientVisitPageRowProjection;
import com.beautica.client.provider.repository.ProviderClientRepository;
import com.beautica.client.provider.service.ClientPhonePolicy;
import com.beautica.client.provider.service.ClientRef;
import com.beautica.client.provider.service.ClientScope.MasterScope;
import com.beautica.client.provider.service.ClientScope.SalonScope;
import com.beautica.client.provider.service.ProviderClientService;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.common.security.PhoneHasher;
import com.beautica.common.security.PhoneHasher.HashedPhone;
import com.beautica.moderation.service.ClientBlockService;
import com.beautica.moderation.service.ClientBlockService.BlockedAmong;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** B508 — {@link ProviderClientService#getCard} / {@link ProviderClientService#getVisits} (CLI-42..45). */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProviderClientService — B508 card + visits")
class ProviderClientServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final String PHONE = "+380501112233";

    @Mock private ProviderClientRepository repository;
    @Mock private ClientBlockService clientBlockService;
    @Mock private PhoneHasher phoneHasher;
    @Mock private AuthorizationService authz;
    @Mock private com.beautica.review.service.ClientRatingSummaryService ratingSummaryService;

    private ProviderClientService service;

    private final UUID salonId = UUID.randomUUID();
    private final UUID masterId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProviderClientService(repository, clientBlockService, phoneHasher,
                new ClientPhonePolicy(authz), ratingSummaryService, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private ClientCardRowProjection cardRow(long total, long completed, long upcoming) {
        ClientCardRowProjection p = mock(ClientCardRowProjection.class);
        lenient().when(p.getTotalVisits()).thenReturn(total);
        lenient().when(p.getCompletedVisits()).thenReturn(completed);
        lenient().when(p.getUpcomingVisits()).thenReturn(upcoming);
        lenient().when(p.getFirstVisitAt()).thenReturn(NOW.minusSeconds(3000));
        lenient().when(p.getLastVisitAt()).thenReturn(NOW.minusSeconds(2000));
        lenient().when(p.getNextVisitAt()).thenReturn(NOW.plusSeconds(1000));
        return p;
    }

    private ClientCardRowProjection registeredRow(int reviewCount, BigDecimal avg) {
        ClientCardRowProjection p = cardRow(6, 2, 1);
        lenient().when(p.getFirstName()).thenReturn("Анна");
        lenient().when(p.getLastName()).thenReturn("Коваль");
        lenient().when(p.getAvatarUrl()).thenReturn("https://cdn.example/a.png");
        lenient().when(p.getReviewCount()).thenReturn(reviewCount);
        lenient().when(p.getAvgRating()).thenReturn(avg);
        return p;
    }

    private ClientCardRowProjection guestRow() {
        ClientCardRowProjection p = cardRow(3, 2, 0);
        lenient().when(p.getGuestPhone()).thenReturn(PHONE);
        lenient().when(p.getGuestName()).thenReturn("Іванна");
        lenient().when(p.getGuestSurname()).thenReturn(" ");
        lenient().when(p.getAnchorBookingId()).thenReturn(bookingId);
        return p;
    }

    private ClientVisitPageRowProjection pageRow(long total, UUID key, Instant startsAt) {
        ClientVisitPageRowProjection p = mock(ClientVisitPageRowProjection.class);
        lenient().when(p.getTotal()).thenReturn(total);
        lenient().when(p.getVisitKey()).thenReturn(key);
        lenient().when(p.getStartsAt()).thenReturn(startsAt);
        return p;
    }

    private ClientVisitItemProjection item(UUID visitKey, UUID booking, String status, UUID salon) {
        ClientVisitItemProjection p = mock(ClientVisitItemProjection.class);
        lenient().when(p.getVisitKey()).thenReturn(visitKey);
        lenient().when(p.getBookingId()).thenReturn(booking);
        lenient().when(p.getServiceName()).thenReturn("Манікюр");
        lenient().when(p.getStatus()).thenReturn(status);
        lenient().when(p.getStartsAt()).thenReturn(NOW);
        lenient().when(p.getSalonId()).thenReturn(salon);
        lenient().when(p.getMasterId()).thenReturn(masterId);
        lenient().when(p.getMasterFirstName()).thenReturn("Олена");
        lenient().when(p.getMasterLastName()).thenReturn("Майстер");
        lenient().when(p.getPriceAtBooking()).thenReturn(new BigDecimal("500.00"));
        return p;
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    // ── card: registered ─────────────────────────────────────────────────────

    @Test
    @DisplayName("registered client card is returned when the user has visits in scope")
    void should_returnRegisteredCard_when_userHasVisitsInScope() {
        when(repository.cardUserBySalon(salonId, userId, utc(NOW))).thenAnswer(i -> registeredRow(2, new BigDecimal("4.00")));
        when(clientBlockService.blockedAmong(any(), eq(List.of(userId)), eq(List.of())))
                .thenReturn(new BlockedAmong(Set.of(userId), Set.of()));

        ClientCardResponse card = service.getCard(new SalonScope(salonId, null), new ClientRef.Registered(userId));

        assertThat(card.kind()).isEqualTo(ClientKind.REGISTERED);
        assertThat(card.userId()).isEqualTo(userId);
        assertThat(card.guestBookingId()).isNull();
        assertThat(card.displayName()).isEqualTo("Анна Коваль");
        assertThat(card.avatarUrl()).isEqualTo("https://cdn.example/a.png");
        assertThat(card.ratingAvg()).isEqualByComparingTo("4.00");
        assertThat(card.blocked()).isTrue();
        assertThat(card.stats()).isEqualTo(new ClientCardResponse.Stats(6, 2, 1));
        assertThat(card.firstVisitAt()).isEqualTo(utc(NOW.minusSeconds(3000)));
        assertThat(card.nextVisitAt()).isEqualTo(utc(NOW.plusSeconds(1000)));
    }

    @Test
    @DisplayName("registered client card hides the phone when it is unverified")
    void should_returnNullPhoneForRegistered_when_phoneIsUnverified() {
        lenient().when(authz.isGuestPhoneAudience()).thenReturn(true);
        when(repository.cardUserBySalon(salonId, userId, utc(NOW))).thenAnswer(i -> registeredRow(1, BigDecimal.ONE));
        when(clientBlockService.blockedAmong(any(), anyCollection(), anyCollection()))
                .thenReturn(new BlockedAmong(Set.of(), Set.of()));

        ClientCardResponse card = service.getCard(new SalonScope(salonId, null), new ClientRef.Registered(userId));

        assertThat(card.phone()).as("unverified: the SQL yields no verified phone").isNull();
        assertThat(card.blocked()).isFalse();
    }

    @Test
    @DisplayName("registered client card shows the verified phone to an allowed audience")
    void should_returnVerifiedPhoneForRegistered_when_audienceIsAllowed() {
        when(authz.isGuestPhoneAudience()).thenReturn(true);
        when(repository.cardUserBySalon(salonId, userId, utc(NOW))).thenAnswer(i -> {
            ClientCardRowProjection p = registeredRow(1, BigDecimal.ONE);
            lenient().when(p.getVerifiedPhone()).thenReturn(PHONE);
            return p;
        });
        when(clientBlockService.blockedAmong(any(), anyCollection(), anyCollection()))
                .thenReturn(new BlockedAmong(Set.of(), Set.of()));

        ClientCardResponse card = service.getCard(new SalonScope(salonId, null), new ClientRef.Registered(userId));

        assertThat(card.phone()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("registered client card hides a verified phone from a disallowed audience")
    void should_returnNullPhoneForRegistered_when_audienceIsNotAllowedEvenIfVerified() {
        when(authz.isGuestPhoneAudience()).thenReturn(false);
        when(repository.cardUserByMaster(masterId, userId, utc(NOW))).thenAnswer(i -> {
            ClientCardRowProjection p = registeredRow(1, BigDecimal.ONE);
            lenient().when(p.getVerifiedPhone()).thenReturn(PHONE);
            return p;
        });
        when(clientBlockService.blockedAmong(any(), anyCollection(), anyCollection()))
                .thenReturn(new BlockedAmong(Set.of(), Set.of()));

        ClientCardResponse card = service.getCard(new MasterScope(masterId, salonId), new ClientRef.Registered(userId));

        assertThat(card.phone()).isNull();
    }

    @Test
    @DisplayName("unrated client card hides the rating and never reads the phone number")
    void should_hideRatingAndNeverReadPhoneNumber_when_clientIsUnrated() {
        when(repository.cardUserByMaster(masterId, userId, utc(NOW))).thenAnswer(i -> registeredRow(0, BigDecimal.ZERO));
        when(clientBlockService.blockedAmong(any(), anyCollection(), anyCollection()))
                .thenReturn(new BlockedAmong(Set.of(), Set.of()));

        ClientCardResponse card = service.getCard(new MasterScope(masterId, salonId), new ClientRef.Registered(userId));

        assertThat(card.ratingAvg()).isNull();
        assertThat(card.phone()).isNull();
        verifyNoInteractions(phoneHasher);
        verify(repository, never()).cardGuestByMaster(any(), any(), any());
    }

    // ── card: guest + phone gate ─────────────────────────────────────────────

    @Test
    @DisplayName("guest card shows the phone to an allowed audience")
    void should_returnGuestPhone_when_audienceIsAllowed() {
        HashedPhone hashed = mock(HashedPhone.class);
        when(authz.isGuestPhoneAudience()).thenReturn(true);
        when(repository.cardGuestBySalon(salonId, bookingId, utc(NOW))).thenAnswer(i -> guestRow());
        when(phoneHasher.tryHash(PHONE)).thenReturn(Optional.of(hashed));
        when(clientBlockService.blockedAmong(any(), eq(List.of()), eq(List.of(hashed))))
                .thenReturn(new BlockedAmong(Set.of(), Set.of(hashed)));

        ClientCardResponse card = service.getCard(new SalonScope(salonId, null), new ClientRef.GuestAnchor(bookingId));

        assertThat(card.kind()).isEqualTo(ClientKind.GUEST);
        assertThat(card.phone()).isEqualTo(PHONE);
        assertThat(card.guestBookingId()).isEqualTo(bookingId);
        assertThat(card.displayName()).isEqualTo("Іванна");
        assertThat(card.ratingAvg()).isNull();
        assertThat(card.userId()).isNull();
        assertThat(card.blocked()).isTrue();
        assertThat(card.stats()).isEqualTo(new ClientCardResponse.Stats(3, 2, 0));
    }

    @Test
    @DisplayName("guest card hides the phone from a disallowed audience")
    void should_returnNullPhone_when_audienceIsNotAllowed() {
        when(authz.isGuestPhoneAudience()).thenReturn(false);
        when(repository.cardGuestByMaster(masterId, bookingId, utc(NOW))).thenAnswer(i -> guestRow());
        when(phoneHasher.tryHash(PHONE)).thenReturn(Optional.empty());

        ClientCardResponse card = service.getCard(new MasterScope(masterId, salonId), new ClientRef.GuestAnchor(bookingId));

        assertThat(card.phone()).isNull();
        assertThat(card.displayName()).isEqualTo("Іванна");
        assertThat(card.blocked()).isFalse();
        verify(clientBlockService, never()).blockedAmong(any(), anyCollection(), anyCollection());
    }

    // ── card: scope x identity dispatch ──────────────────────────────────────

    @Test
    @DisplayName("owner-master mode card uses the salon-and-master queries")
    void should_useSalonMasterQueries_when_ownerMasterModeScope() {
        when(repository.cardUserBySalonAndMaster(salonId, masterId, userId, utc(NOW)))
                .thenAnswer(i -> registeredRow(0, null));
        when(repository.cardGuestBySalonAndMaster(salonId, masterId, bookingId, utc(NOW))).thenAnswer(i -> guestRow());
        when(phoneHasher.tryHash(PHONE)).thenReturn(Optional.empty());
        when(clientBlockService.blockedAmong(any(), anyCollection(), anyCollection()))
                .thenReturn(new BlockedAmong(Set.of(), Set.of()));
        SalonScope scope = new SalonScope(salonId, masterId);

        service.getCard(scope, new ClientRef.Registered(userId));
        service.getCard(scope, new ClientRef.GuestAnchor(bookingId));

        verify(repository).cardUserBySalonAndMaster(salonId, masterId, userId, utc(NOW));
        verify(repository).cardGuestBySalonAndMaster(salonId, masterId, bookingId, utc(NOW));
        verifyNoMoreInteractions(repository);
    }

    // ── card: servedByMasters (CLI-73) ───────────────────────────────────────

    @Test
    @DisplayName("salon card maps servedByMasters in column order, names joined and trimmed, null avatar kept")
    void should_mapServedMastersInColumnOrder_when_salonScope() throws Exception {
        UUID recent = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        Map<String, Object> olderChip = new LinkedHashMap<>();
        olderChip.put("masterId", older);
        olderChip.put("firstName", "Стара");
        olderChip.put("lastName", null);
        olderChip.put("avatarUrl", null);
        String json = new ObjectMapper().writeValueAsString(List.of(
                Map.of("masterId", recent, "firstName", "Олена", "lastName", " Нова ",
                        "avatarUrl", "https://cdn.example/m.png"),
                olderChip));
        when(repository.cardUserBySalon(salonId, userId, utc(NOW))).thenAnswer(i -> {
            ClientCardRowProjection row = registeredRow(0, null);
            when(row.getServedByMasters()).thenReturn(json);
            return row;
        });
        when(clientBlockService.blockedAmong(any(), anyCollection(), anyCollection()))
                .thenReturn(new BlockedAmong(Set.of(), Set.of()));

        ClientCardResponse card = service.getCard(new SalonScope(salonId, null), new ClientRef.Registered(userId));

        assertThat(card.servedByMasters()).containsExactly(
                new ClientCardResponse.ServedMaster(recent, "Олена Нова", "https://cdn.example/m.png"),
                new ClientCardResponse.ServedMaster(older, "Стара", null));
    }

    @Test
    @DisplayName("salon card with an empty '[]' column carries no master chips")
    void should_returnEmptyServedMasters_when_salonCardHasNone() {
        when(repository.cardUserBySalon(salonId, userId, utc(NOW))).thenAnswer(i -> {
            ClientCardRowProjection row = registeredRow(0, null);
            when(row.getServedByMasters()).thenReturn("[]");
            return row;
        });
        when(clientBlockService.blockedAmong(any(), anyCollection(), anyCollection()))
                .thenReturn(new BlockedAmong(Set.of(), Set.of()));

        ClientCardResponse card = service.getCard(new SalonScope(salonId, null), new ClientRef.Registered(userId));

        assertThat(card.servedByMasters()).isEmpty();
    }

    @Test
    @DisplayName("master-scope card (NULL column) carries an empty, non-null chip list")
    void should_returnEmptyServedMasters_when_masterScopeColumnIsNull() {
        when(repository.cardGuestByMaster(masterId, bookingId, utc(NOW))).thenAnswer(i -> guestRow());
        when(phoneHasher.tryHash(PHONE)).thenReturn(Optional.empty());

        ClientCardResponse card = service.getCard(new MasterScope(masterId, null), new ClientRef.GuestAnchor(bookingId));

        assertThat(card.servedByMasters()).isEmpty();
    }

    @Test
    @DisplayName("malformed servedByMasters column fails without echoing staff names")
    void should_throwWithoutPayload_when_servedMastersColumnIsMalformed() {
        when(repository.cardUserBySalon(salonId, userId, utc(NOW))).thenAnswer(i -> {
            ClientCardRowProjection row = registeredRow(0, null);
            when(row.getServedByMasters()).thenReturn("[{\"firstName\":\"Олена\"");
            return row;
        });

        assertThatThrownBy(() -> service.getCard(new SalonScope(salonId, null), new ClientRef.Registered(userId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("Олена");
    }

    // ── card: uniform 404 ────────────────────────────────────────────────────

    @Test
    @DisplayName("card lookup fails with not found when a registered client has no visit in scope")
    void should_throwNotFound_when_registeredHasNoVisitInScope() {
        when(repository.cardUserBySalon(salonId, userId, utc(NOW))).thenAnswer(i -> cardRow(0, 0, 0));

        assertThatThrownBy(() -> service.getCard(new SalonScope(salonId, null), new ClientRef.Registered(userId)))
                .isInstanceOf(NotFoundException.class);
        verify(clientBlockService, never()).blockedAmong(any(), anyCollection(), anyCollection());
    }

    @Test
    @DisplayName("card lookup gives the same not found when the guest anchor is invalid")
    void should_throwSameNotFound_when_guestAnchorIsInvalid() {
        when(repository.cardGuestBySalon(salonId, bookingId, utc(NOW))).thenAnswer(i -> cardRow(0, 0, 0));

        NotFoundException guest = org.junit.jupiter.api.Assertions.assertThrows(NotFoundException.class,
                () -> service.getCard(new SalonScope(salonId, null), new ClientRef.GuestAnchor(bookingId)));
        when(repository.cardUserBySalon(salonId, userId, utc(NOW))).thenAnswer(i -> cardRow(0, 0, 0));
        NotFoundException user = org.junit.jupiter.api.Assertions.assertThrows(NotFoundException.class,
                () -> service.getCard(new SalonScope(salonId, null), new ClientRef.Registered(userId)));

        assertThat(guest.getMessage()).isEqualTo(user.getMessage());
    }

    // ── visits ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("multi-row visits are grouped into one visit each, in page order")
    void should_groupItemsUnderTheirVisitKeepingPageOrder_when_pageHasMultiRowVisits() {
        UUID newer = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID b1 = UUID.randomUUID();
        UUID b2 = UUID.randomUUID();
        UUID b3 = UUID.randomUUID();
        when(repository.visitsUserBySalon(salonId, userId, 20, 0L)).thenAnswer(i -> List.of(
                pageRow(2, newer, NOW), pageRow(2, older, NOW.minusSeconds(86400))));
        when(repository.visitItemsBySalon(eq(salonId), anyCollection())).thenAnswer(i -> List.of(
                item(older, b3, "COMPLETED", salonId), item(newer, b1, "COMPLETED", salonId),
                item(newer, b2, "CANCELLED", salonId)));

        Page<ClientVisitResponse> page = service.getVisits(
                new SalonScope(salonId, null), new ClientRef.Registered(userId), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(ClientVisitResponse::visitKey).containsExactly(newer, older);
        assertThat(page.getContent().get(0).items()).extracting(ClientVisitResponse.Item::bookingId)
                .containsExactly(b1, b2);
        assertThat(page.getContent().get(0).items().get(1).status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(page.getContent().get(0).items().get(0).masterDisplayName()).isEqualTo("Олена Майстер");
        assertThat(page.getContent().get(0).salonId()).isEqualTo(salonId);
        assertThat(page.getContent().get(1).items()).extracting(ClientVisitResponse.Item::bookingId)
                .containsExactly(b3);
    }

    @Test
    @DisplayName("second page request passes the right offset and size to the query")
    void should_passOffsetAndSizeToThePageQuery_when_secondPageRequested() {
        UUID key = UUID.randomUUID();
        when(repository.visitsGuestByMaster(masterId, bookingId, 20, 20L))
                .thenAnswer(i -> List.of(pageRow(40, key, NOW)));
        when(repository.visitItemsByMaster(eq(masterId), anyCollection()))
                .thenAnswer(i -> List.of(item(key, UUID.randomUUID(), "COMPLETED", null)));

        Page<ClientVisitResponse> page = service.getVisits(
                new MasterScope(masterId, null), new ClientRef.GuestAnchor(bookingId), PageRequest.of(1, 20));

        assertThat(page.getTotalElements()).isEqualTo(40);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).salonId()).isNull();
    }

    @Test
    @DisplayName("page beyond the end skips the items query and keeps the total")
    void should_skipItemsQueryAndKeepTotal_when_pageIsBeyondTheEnd() {
        when(repository.visitsUserBySalonAndMaster(salonId, masterId, userId, 20, 200L))
                .thenAnswer(i -> List.of(pageRow(3, null, null)));

        Page<ClientVisitResponse> page = service.getVisits(
                new SalonScope(salonId, masterId), new ClientRef.Registered(userId), PageRequest.of(10, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(3);
        verify(repository, never()).visitItemsBySalonAndMaster(any(), any(), anyCollection());
    }

    @Test
    @DisplayName("visits lookup fails with not found when there is no visit in scope")
    void should_throwNotFound_when_noVisitInScope() {
        when(repository.visitsGuestBySalon(eq(salonId), eq(bookingId), anyInt(), anyLong()))
                .thenAnswer(i -> List.of(pageRow(0, null, null)));

        assertThatThrownBy(() -> service.getVisits(
                new SalonScope(salonId, null), new ClientRef.GuestAnchor(bookingId), PageRequest.of(0, 20)))
                .isInstanceOf(NotFoundException.class);
        verify(repository, never()).visitItemsBySalon(any(), anyCollection());
    }

    // ── B526: getOwnRatings dispatch (CLI-59) ────────────────────────────────

    private com.beautica.client.provider.repository.ClientOwnRatingProjection ratingRow(UUID booking, int stars) {
        com.beautica.client.provider.repository.ClientOwnRatingProjection p =
                mock(com.beautica.client.provider.repository.ClientOwnRatingProjection.class);
        when(p.getBookingId()).thenReturn(booking);
        when(p.getVisitAt()).thenReturn(NOW.minusSeconds(500));
        when(p.getServiceName()).thenReturn("Манікюр");
        when(p.getStars()).thenReturn(stars);
        return p;
    }

    private void assertSingleRow(Page<com.beautica.client.provider.dto.ClientOwnRatingResponse> page, UUID booking,
                                 int stars) {
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(
                        com.beautica.client.provider.dto.ClientOwnRatingResponse::bookingId,
                        com.beautica.client.provider.dto.ClientOwnRatingResponse::visitAt,
                        com.beautica.client.provider.dto.ClientOwnRatingResponse::serviceName,
                        com.beautica.client.provider.dto.ClientOwnRatingResponse::stars)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(booking,
                        NOW.minusSeconds(500).atOffset(ZoneOffset.UTC), "Манікюр", stars));
    }

    @Test
    @DisplayName("registered client in salon scope uses the salon queries")
    void should_useSalonQueries_when_registeredClientInSalonScope() {
        var row = ratingRow(bookingId, 5);
        when(repository.existsUserBySalon(salonId, userId)).thenReturn(true);
        when(repository.ratingsBySalon(salonId, userId, 20, 0L)).thenReturn(List.of(row));
        when(repository.countRatingsBySalon(salonId, userId)).thenReturn(1L);

        Page<com.beautica.client.provider.dto.ClientOwnRatingResponse> page = service.getOwnRatings(
                new SalonScope(salonId, null), new ClientRef.Registered(userId), PageRequest.of(0, 20));

        assertSingleRow(page, bookingId, 5);
        verify(repository, never()).existsUserBySalonAndMaster(any(), any(), any());
        verify(repository, never()).existsUserByMaster(any(), any());
        verify(repository, never()).existsGuestBySalon(any(), any());
    }

    @Test
    @DisplayName("registered client in salon-and-master scope uses the combined queries")
    void should_useSalonAndMasterQueries_when_registeredClientInSalonMasterScope() {
        var row = ratingRow(bookingId, 3);
        when(repository.existsUserBySalonAndMaster(salonId, masterId, userId)).thenReturn(true);
        when(repository.ratingsBySalonAndMaster(salonId, masterId, userId, 10, 0L)).thenReturn(List.of(row));
        when(repository.countRatingsBySalonAndMaster(salonId, masterId, userId)).thenReturn(1L);

        Page<com.beautica.client.provider.dto.ClientOwnRatingResponse> page = service.getOwnRatings(
                new SalonScope(salonId, masterId), new ClientRef.Registered(userId), PageRequest.of(0, 10));

        assertSingleRow(page, bookingId, 3);
        verify(repository, never()).existsUserBySalon(any(), any());
        verify(repository, never()).ratingsBySalon(any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("registered client in master scope uses the master queries")
    void should_useMasterQueries_when_registeredClientInMasterScope() {
        var row = ratingRow(bookingId, 4);
        when(repository.existsUserByMaster(masterId, userId)).thenReturn(true);
        when(repository.ratingsByMaster(masterId, userId, 20, 0L)).thenReturn(List.of(row));
        when(repository.countRatingsByMaster(masterId, userId)).thenReturn(1L);

        Page<com.beautica.client.provider.dto.ClientOwnRatingResponse> page = service.getOwnRatings(
                new MasterScope(masterId, salonId), new ClientRef.Registered(userId), PageRequest.of(0, 20));

        assertSingleRow(page, bookingId, 4);
        verify(repository, never()).existsUserBySalon(any(), any());
        verify(repository, never()).ratingsBySalon(any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("guest in salon scope gets an empty page without a page query")
    void should_returnEmptyPageWithoutPageQuery_when_guestAnchorInSalonScope() {
        when(repository.existsGuestBySalon(salonId, bookingId)).thenReturn(true);

        Page<com.beautica.client.provider.dto.ClientOwnRatingResponse> page = service.getOwnRatings(
                new SalonScope(salonId, null), new ClientRef.GuestAnchor(bookingId), PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(repository, never()).existsUserBySalon(any(), any());
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("guest in salon-and-master scope gets an empty page without a page query")
    void should_returnEmptyPageWithoutPageQuery_when_guestAnchorInSalonMasterScope() {
        when(repository.existsGuestBySalonAndMaster(salonId, masterId, bookingId)).thenReturn(true);

        Page<com.beautica.client.provider.dto.ClientOwnRatingResponse> page = service.getOwnRatings(
                new SalonScope(salonId, masterId), new ClientRef.GuestAnchor(bookingId), PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(repository, never()).existsGuestBySalon(any(), any());
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("guest in master scope gets an empty page without a page query")
    void should_returnEmptyPageWithoutPageQuery_when_guestAnchorInMasterScope() {
        when(repository.existsGuestByMaster(masterId, bookingId)).thenReturn(true);

        Page<com.beautica.client.provider.dto.ClientOwnRatingResponse> page = service.getOwnRatings(
                new MasterScope(masterId, salonId), new ClientRef.GuestAnchor(bookingId), PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(repository, never()).existsGuestBySalon(any(), any());
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("registered client outside the salon scope is not found")
    void should_throwClientNotFound_when_registeredClientNotInSalonScope() {
        when(repository.existsUserBySalon(salonId, userId)).thenReturn(false);

        assertThatThrownBy(() -> service.getOwnRatings(
                new SalonScope(salonId, null), new ClientRef.Registered(userId), PageRequest.of(0, 20)))
                .isInstanceOf(NotFoundException.class);
        verify(repository, never()).ratingsBySalon(any(), any(), anyInt(), anyLong());
        verify(repository, never()).countRatingsBySalon(any(), any());
    }

    @Test
    @DisplayName("registered client outside the salon-and-master scope is not found")
    void should_throwClientNotFound_when_registeredClientNotInSalonMasterScope() {
        when(repository.existsUserBySalonAndMaster(salonId, masterId, userId)).thenReturn(false);

        assertThatThrownBy(() -> service.getOwnRatings(
                new SalonScope(salonId, masterId), new ClientRef.Registered(userId), PageRequest.of(0, 20)))
                .isInstanceOf(NotFoundException.class);
        verify(repository, never()).ratingsBySalonAndMaster(any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("registered client outside the master scope is not found")
    void should_throwClientNotFound_when_registeredClientNotInMasterScope() {
        when(repository.existsUserByMaster(masterId, userId)).thenReturn(false);

        assertThatThrownBy(() -> service.getOwnRatings(
                new MasterScope(masterId, salonId), new ClientRef.Registered(userId), PageRequest.of(0, 20)))
                .isInstanceOf(NotFoundException.class);
        verify(repository, never()).ratingsByMaster(any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("guest anchor outside every scope is not found")
    void should_throwClientNotFound_when_guestAnchorNotInScope() {
        when(repository.existsGuestBySalon(salonId, bookingId)).thenReturn(false);
        when(repository.existsGuestBySalonAndMaster(salonId, masterId, bookingId)).thenReturn(false);
        when(repository.existsGuestByMaster(masterId, bookingId)).thenReturn(false);

        assertThatThrownBy(() -> service.getOwnRatings(new SalonScope(salonId, null),
                new ClientRef.GuestAnchor(bookingId), PageRequest.of(0, 20))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getOwnRatings(new SalonScope(salonId, masterId),
                new ClientRef.GuestAnchor(bookingId), PageRequest.of(0, 20))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getOwnRatings(new MasterScope(masterId, salonId),
                new ClientRef.GuestAnchor(bookingId), PageRequest.of(0, 20))).isInstanceOf(NotFoundException.class);
        verifyNoMoreInteractions(repository);
    }

    // ── phone policy seam ────────────────────────────────────────────────────

    @Test
    @DisplayName("phone policy returns no phone for any client kind to a disallowed audience")
    void should_returnNoPhoneForAnyKind_when_audienceIsNotAllowed() {
        ClientPhonePolicy policy = new ClientPhonePolicy(authz);
        when(authz.isGuestPhoneAudience()).thenReturn(false);

        assertThat(policy.phoneFor(PHONE)).isNull();
        assertThat(policy.phoneFor(PHONE)).isNull();
    }

    @Test
    @DisplayName("phone policy returns the phone to an allowed audience")
    void should_returnPhone_when_audienceIsAllowed() {
        ClientPhonePolicy policy = new ClientPhonePolicy(authz);
        lenient().when(authz.isGuestPhoneAudience()).thenReturn(true);

        assertThat(policy.phoneFor(PHONE)).isEqualTo(PHONE);
        assertThat(policy.phoneFor(PHONE)).as("B518: verified registered phone").isEqualTo(PHONE);
        assertThat(policy.phoneFor(null)).as("unverified/absent").isNull();
        assertThat(policy.phoneFor(null)).isNull();
    }
}

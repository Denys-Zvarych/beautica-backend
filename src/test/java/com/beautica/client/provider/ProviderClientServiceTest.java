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
import java.util.List;
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

    private ProviderClientService service;

    private final UUID salonId = UUID.randomUUID();
    private final UUID masterId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProviderClientService(repository, clientBlockService, phoneHasher,
                new ClientPhonePolicy(authz), Clock.fixed(NOW, ZoneOffset.UTC));
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
    @DisplayName("should_returnRegisteredCard_when_userHasVisitsInScope")
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
    @DisplayName("should_returnNullPhoneForRegistered_when_audienceIsAllowed")
    void should_returnNullPhoneForRegistered_when_audienceIsAllowed() {
        lenient().when(authz.isGuestPhoneAudience()).thenReturn(true);
        when(repository.cardUserBySalon(salonId, userId, utc(NOW))).thenAnswer(i -> registeredRow(1, BigDecimal.ONE));
        when(clientBlockService.blockedAmong(any(), anyCollection(), anyCollection()))
                .thenReturn(new BlockedAmong(Set.of(), Set.of()));

        ClientCardResponse card = service.getCard(new SalonScope(salonId, null), new ClientRef.Registered(userId));

        assertThat(card.phone()).as("registered phone is B518").isNull();
        assertThat(card.blocked()).isFalse();
    }

    @Test
    @DisplayName("should_hideRatingAndNeverReadPhoneNumber_when_clientIsUnrated")
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
    @DisplayName("should_returnGuestPhone_when_audienceIsAllowed")
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
    @DisplayName("should_returnNullPhone_when_audienceIsNotAllowed")
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
    @DisplayName("should_useSalonMasterQueries_when_ownerMasterModeScope")
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

    // ── card: uniform 404 ────────────────────────────────────────────────────

    @Test
    @DisplayName("should_throwNotFound_when_registeredHasNoVisitInScope")
    void should_throwNotFound_when_registeredHasNoVisitInScope() {
        when(repository.cardUserBySalon(salonId, userId, utc(NOW))).thenAnswer(i -> cardRow(0, 0, 0));

        assertThatThrownBy(() -> service.getCard(new SalonScope(salonId, null), new ClientRef.Registered(userId)))
                .isInstanceOf(NotFoundException.class);
        verify(clientBlockService, never()).blockedAmong(any(), anyCollection(), anyCollection());
    }

    @Test
    @DisplayName("should_throwSameNotFound_when_guestAnchorIsInvalid")
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
    @DisplayName("should_groupItemsUnderTheirVisitKeepingPageOrder_when_pageHasMultiRowVisits")
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
    @DisplayName("should_passOffsetAndSizeToThePageQuery_when_secondPageRequested")
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
    @DisplayName("should_skipItemsQueryAndKeepTotal_when_pageIsBeyondTheEnd")
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
    @DisplayName("should_throwNotFound_when_noVisitInScope")
    void should_throwNotFound_when_noVisitInScope() {
        when(repository.visitsGuestBySalon(eq(salonId), eq(bookingId), anyInt(), anyLong()))
                .thenAnswer(i -> List.of(pageRow(0, null, null)));

        assertThatThrownBy(() -> service.getVisits(
                new SalonScope(salonId, null), new ClientRef.GuestAnchor(bookingId), PageRequest.of(0, 20)))
                .isInstanceOf(NotFoundException.class);
        verify(repository, never()).visitItemsBySalon(any(), anyCollection());
    }

    // ── phone policy seam ────────────────────────────────────────────────────

    @Test
    @DisplayName("should_returnNoPhoneForAnyKind_when_audienceIsNotAllowed")
    void should_returnNoPhoneForAnyKind_when_audienceIsNotAllowed() {
        ClientPhonePolicy policy = new ClientPhonePolicy(authz);
        when(authz.isGuestPhoneAudience()).thenReturn(false);

        assertThat(policy.phoneFor(ClientKind.GUEST, PHONE, null)).isNull();
        assertThat(policy.phoneFor(ClientKind.REGISTERED, null, userId)).isNull();
    }

    @Test
    @DisplayName("should_returnGuestPhoneOnly_when_audienceIsAllowed")
    void should_returnGuestPhoneOnly_when_audienceIsAllowed() {
        ClientPhonePolicy policy = new ClientPhonePolicy(authz);
        lenient().when(authz.isGuestPhoneAudience()).thenReturn(true);

        assertThat(policy.phoneFor(ClientKind.GUEST, PHONE, null)).isEqualTo(PHONE);
        assertThat(policy.phoneFor(ClientKind.REGISTERED, PHONE, userId)).as("registered stays null until B518").isNull();
        assertThat(policy.phoneFor(ClientKind.GUEST, null, null)).isNull();
    }
}

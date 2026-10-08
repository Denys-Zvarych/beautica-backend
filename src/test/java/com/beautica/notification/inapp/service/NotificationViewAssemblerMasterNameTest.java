package com.beautica.notification.inapp.service;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.master.entity.Master;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.service.BookingVisit;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("NotificationViewAssembler — performing master name guard")
class NotificationViewAssemblerMasterNameTest {

    private static Master master(UUID id) {
        Master m = mock(Master.class);
        when(m.getId()).thenReturn(id);
        return m;
    }

    private static BookingVisit visitOf(Master... masters) {
        BookingVisit visit = mock(BookingVisit.class);
        List<Booking> items = java.util.Arrays.stream(masters).map(m -> {
            Booking b = mock(Booking.class);
            when(b.getMaster()).thenReturn(m);
            return b;
        }).toList();
        when(visit.items()).thenReturn(items);
        return visit;
    }

    @Test
    @DisplayName("single-master when no visit or all legs share one master")
    void should_beSingleMaster_when_sameMasterOrNoVisit() {
        Master m = master(UUID.randomUUID());

        assertThat(NotificationViewAssembler.isSingleMaster(null)).isTrue();
        assertThat(NotificationViewAssembler.isSingleMaster(visitOf(m, m))).isTrue();
    }

    @Test
    @DisplayName("not single-master when a visit spans two masters")
    void should_notBeSingleMaster_when_visitSpansTwoMasters() {
        BookingVisit mixed = visitOf(master(UUID.randomUUID()), master(UUID.randomUUID()));

        assertThat(NotificationViewAssembler.isSingleMaster(mixed)).isFalse();
    }

    @Test
    @DisplayName("name is null for a multi-master visit, present for single-master")
    void should_returnNullName_when_notSingleMaster() {
        Master m = mock(Master.class);
        when(m.displayFirstName()).thenReturn("Ірина");
        when(m.displayLastName()).thenReturn("Мельник");
        User user = mock(User.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(m.getUser()).thenReturn(user);
        Salon salon = mock(Salon.class);

        assertThat(NotificationViewAssembler.performingMasterName(
                InAppNotificationType.BOOKING_CREATED, m, salon, UUID.randomUUID(), Role.SALON_OWNER, false))
                .isNull();
        assertThat(NotificationViewAssembler.performingMasterName(
                InAppNotificationType.BOOKING_CREATED, m, salon, UUID.randomUUID(), Role.SALON_OWNER, true))
                .isEqualTo("Ірина Мельник");
    }

    private static Master namedMaster(UUID masterUserId) {
        Master m = mock(Master.class);
        when(m.displayFirstName()).thenReturn("Ірина");
        when(m.displayLastName()).thenReturn("Мельник");
        User user = mock(User.class);
        when(user.getId()).thenReturn(masterUserId);
        when(m.getUser()).thenReturn(user);
        return m;
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CLIENT", "SALON_MASTER", "INDEPENDENT_MASTER"})
    @DisplayName("name is null for every non-salon-staff recipient role")
    void should_returnNullName_when_recipientIsNotOwnerOrAdmin(Role role) {
        assertThat(NotificationViewAssembler.performingMasterName(
                InAppNotificationType.BOOKING_CREATED, namedMaster(UUID.randomUUID()), mock(Salon.class),
                UUID.randomUUID(), role, true)).as("role=%s", role).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SALON_OWNER", "SALON_ADMIN"})
    @DisplayName("name is present for owner and admin on the three eligible types")
    void should_returnName_when_ownerOrAdminOnEligibleTypes(Role role) {
        for (InAppNotificationType type : List.of(InAppNotificationType.BOOKING_CREATED,
                InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, InAppNotificationType.BOOKING_RESCHEDULED)) {
            assertThat(NotificationViewAssembler.performingMasterName(
                    type, namedMaster(UUID.randomUUID()), mock(Salon.class), UUID.randomUUID(), role, true))
                    .as("role=%s type=%s", role, type).isEqualTo("Ірина Мельник");
        }
    }

    @ParameterizedTest
    @EnumSource(value = InAppNotificationType.class, names = {"BOOKING_CREATED",
            "BOOKING_CANCELLED_BY_CLIENT", "BOOKING_RESCHEDULED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("name is null for every notification type outside the three eligible ones")
    void should_returnNullName_when_typeOutsideTheThree(InAppNotificationType type) {
        assertThat(NotificationViewAssembler.performingMasterName(
                type, namedMaster(UUID.randomUUID()), mock(Salon.class), UUID.randomUUID(), Role.SALON_OWNER, true))
                .as("type=%s", type).isNull();
    }

    @Test
    @DisplayName("name is null for an independent-master booking (no salon)")
    void should_returnNullName_when_noSalon() {
        assertThat(NotificationViewAssembler.performingMasterName(
                InAppNotificationType.BOOKING_CREATED, namedMaster(UUID.randomUUID()), null,
                UUID.randomUUID(), Role.SALON_OWNER, true)).isNull();
    }

    @Test
    @DisplayName("name is null when the recipient is the performing master (owner-as-master)")
    void should_returnNullName_when_recipientIsThePerformingMaster() {
        UUID ownerId = UUID.randomUUID();

        assertThat(NotificationViewAssembler.performingMasterName(
                InAppNotificationType.BOOKING_CREATED, namedMaster(ownerId), mock(Salon.class),
                ownerId, Role.SALON_OWNER, true)).isNull();
    }
}

package com.beautica.booking.domain;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.service.entity.ServiceDefinition;

/**
 * The service name shown to an account-less guest (SMS, public cancel page) for one booking.
 *
 * <p>A STAFF walk-in gets the PLATFORM service type ({@code ServiceType.nameUk}), never the provider's custom
 * {@code ServiceDefinition.name}: the walk-in phone was typed by the provider and never opted in, so free text an
 * account controls must not reach a Beautica-branded message (security LOW, 2026-08-19; the walk-in confirmation in
 * {@code StaffBookingService} follows the same rule). A LINK guest keeps the custom name, unchanged.
 *
 * <p>Callers must run inside the transaction that loaded {@code booking} (the STAFF branch reads
 * {@code serviceDefinition.serviceType}, which may be lazy).
 */
public final class GuestSmsServiceName {

    private GuestSmsServiceName() {
    }

    public static String of(Booking booking) {
        ServiceDefinition definition = booking.getMasterService().getServiceDefinition();
        return booking.getBookingSource() == BookingSource.STAFF
                ? definition.getServiceType().getNameUk()
                : definition.getName();
    }
}

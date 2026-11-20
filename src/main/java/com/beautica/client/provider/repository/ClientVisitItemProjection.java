package com.beautica.client.provider.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** B508 — one booking row of a visit, in the viewer's scope. */
public interface ClientVisitItemProjection {

    UUID getVisitKey();

    UUID getBookingId();

    String getServiceName();

    String getStatus();

    Instant getStartsAt();

    UUID getSalonId();

    UUID getMasterId();

    String getMasterFirstName();

    String getMasterLastName();

    BigDecimal getPriceAtBooking();

    BigDecimal getPriceMaxAtBooking();
}

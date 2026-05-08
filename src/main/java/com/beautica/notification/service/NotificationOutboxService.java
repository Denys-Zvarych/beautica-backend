package com.beautica.notification.service;

import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class NotificationOutboxService {

    // Implementations MUST only write to the outbox table (one INSERT). Do not call NotificationService here — the drain worker calls it outside the transaction.

    public void enqueueNewBooking(UUID bookingId) {}

    public void enqueueStatusChanged(UUID bookingId) {}

    public void enqueueClientCancelled(UUID bookingId) {}
}

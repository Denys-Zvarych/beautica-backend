package com.beautica.notification;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class NotificationOutboxService {

    public void enqueueNewBooking(UUID bookingId) {
    }

    public void enqueueBookingStatusChange(UUID bookingId) {
    }
}

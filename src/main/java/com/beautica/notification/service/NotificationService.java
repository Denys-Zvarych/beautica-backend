package com.beautica.notification.service;

import com.beautica.booking.entity.Booking;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    public void notifyNewBooking(Booking booking) {}

    public void notifyBookingStatusChanged(Booking booking) {}

    public void notifyClientCancelled(Booking booking) {}

    public void sendInviteEmail(String email, String token, String salonName) {}
}

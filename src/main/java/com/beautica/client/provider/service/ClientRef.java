package com.beautica.client.provider.service;

import java.util.UUID;

/**
 * B508 — which client a card / visit page is about. A registered client is their account; a guest is identified by
 * ANY of their bookings in the viewer's scope (never by phone or hash, D1) — the server reads that booking's phone.
 */
public sealed interface ClientRef {

    record Registered(UUID userId) implements ClientRef {
    }

    record GuestAnchor(UUID bookingId) implements ClientRef {
    }
}

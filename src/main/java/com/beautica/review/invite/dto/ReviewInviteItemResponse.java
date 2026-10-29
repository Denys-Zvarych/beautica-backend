package com.beautica.review.invite.dto;

import java.util.UUID;

/**
 * Phase 401 — one reviewable service of the invite's visit on the guest review page.
 *
 * @param bookingId   the booking the guest rates (echoed back in the submit)
 * @param serviceName the service's name
 * @param masterName  the performing master's display name
 */
public record ReviewInviteItemResponse(UUID bookingId, String serviceName, String masterName) {
}

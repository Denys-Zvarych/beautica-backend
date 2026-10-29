package com.beautica.review.invite;

import com.beautica.booking.service.AppointmentTransitionService;
import com.beautica.common.security.PhoneHasher;
import com.beautica.review.invite.service.ReviewInviteSendJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 400 — {@code app.booking.review-invite.enabled} off (the default) with booking SMS ON: no invite row, no SMS
 * (zero Turbosms calls), and a row left from before the switch-off is skipped rather than sent.
 */
@TestPropertySource(properties = "app.booking.sms.enabled=true")
@DisplayName("Review invites flag off — phase 400 IT")
class ReviewInviteInvitesDisabledIT extends AbstractReviewInviteIT {

    @Autowired
    private AppointmentTransitionService appointmentTransitionService;

    @Autowired
    private ReviewInviteSendJob job;

    @Autowired
    private PhoneHasher phoneHasher;

    @Test
    @DisplayName("completing a LINK visit creates no invite row and sends nothing")
    void should_createNothing_when_invitesDisabled() {
        UUID appointmentId = fixtures.linkVisit("CONFIRMED", GUEST_PHONE, POLICY);
        fixtures.visitItem(appointmentId, masterId, masterServiceId, "LINK", "CONFIRMED", GUEST_PHONE, 3);

        authenticateAsMaster();
        appointmentTransitionService.completeAppointment(masterUserId, appointmentId);
        job.tick(MIDDAY);

        assertThat(inviteCount()).isZero();
        assertThat(turbosmsRequests()).isEmpty();
    }

    @Test
    @DisplayName("a row scheduled before the switch-off is SKIPPED (INVITES_DISABLED), never sent")
    void should_skipLeftoverRow_when_invitesDisabled() {
        UUID appointmentId = completedLinkVisit(GUEST_PHONE, POLICY);
        UUID inviteId = scheduledInvite(appointmentId, phoneHasher.hash(GUEST_PHONE).hash(), MIDDAY.minusSeconds(60));

        job.tick(MIDDAY);

        assertThat(inviteRow(inviteId).get("skip_reason")).isEqualTo("INVITES_DISABLED");
        assertThat(turbosmsRequests()).isEmpty();
    }
}

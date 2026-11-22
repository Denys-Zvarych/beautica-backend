package com.beautica.review.invite;

import com.beautica.booking.enums.AttachReason;
import com.beautica.client.service.ClientPhoneAttachService;
import com.beautica.common.security.PhoneHasher;
import com.beautica.review.invite.service.ReviewInviteSendJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B527 (REV-37) — a web review invite scheduled while the visit was still a guest's is SKIPPED at send time once the
 * visit has been attached to a client account (the client reviews in the app; no SMS to the guest number).
 */
@TestPropertySource(properties = {
        "app.frontend.base-url=https://beautica.app",
        "app.booking.sms.enabled=true",
        "app.booking.review-invite.enabled=true",
        "app.booking.review-invite.min-policy-version=2026-10-20"
})
@DisplayName("ReviewInviteSendJob — invite of a booking attached after scheduling (B527)")
class ReviewInviteAttachedBookingIT extends AbstractReviewInviteIT {

    @Autowired private ReviewInviteSendJob job;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private ClientPhoneAttachService attachService;
    @Autowired private TransactionTemplate transactionTemplate;

    private UUID verifiedClient(String phone) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, phone_number, "
                + "phone_verified_at, is_active, email_verified) VALUES (?, ?, 'x', 'CLIENT', 'Ірина', ?, NOW(), "
                + "true, true)", id, "inv-" + id + "@beautica.test", phone);
        return id;
    }

    @Test
    @DisplayName("18: a due invite for a visit attached after scheduling is SKIPPED, nothing is sent")
    void should_skipInviteAtSendTime_when_visitWasAttachedAfterScheduling() {
        UUID appointmentId = completedLinkVisit(GUEST_PHONE, POLICY);
        UUID invite = scheduledInvite(appointmentId, phoneHasher.hash(GUEST_PHONE).hash(), MIDDAY.minusSeconds(60));
        verifiedClient(GUEST_PHONE);
        transactionTemplate.executeWithoutResult(
                st -> attachService.attachByPhone(GUEST_PHONE, AttachReason.PHONE_VERIFIED));

        job.tick(MIDDAY);

        assertThat(inviteRow(invite).get("status")).isEqualTo("SKIPPED");
        assertThat(turbosmsRequests()).isEmpty();
    }

    @Test
    @DisplayName("18 control: the same invite for a visit nobody attached is sent")
    void should_sendInvite_when_visitIsStillAGuests() {
        UUID appointmentId = completedLinkVisit(GUEST_PHONE, POLICY);
        UUID invite = scheduledInvite(appointmentId, phoneHasher.hash(GUEST_PHONE).hash(), MIDDAY.minusSeconds(60));

        job.tick(MIDDAY);

        assertThat(inviteRow(invite).get("status")).isEqualTo("SENT");
        assertThat(turbosmsRequests()).hasSize(1);
    }
}

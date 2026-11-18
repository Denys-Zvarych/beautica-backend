package com.beautica.support;

import com.beautica.auth.InvitePersistenceService;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * B520 — shared fixtures for every IT that needs a staff member who joined by a PHONE invite: a fresh UA mobile per
 * call, a redeemable registration ticket seeded straight into {@code phone_registration_tickets} (the B514 OTP round
 * trip is pinned by its own ITs; a fixture does not need to pay for it), the raw invite token captured from a
 * {@code @SpyBean InvitePersistenceService}, and the accept body.
 */
public final class StaffInviteFixtures {

    private StaffInviteFixtures() {
    }

    /** A fresh Vodafone-range +380 mobile per call — never collides on the verified-phone unique index. */
    public static String newPhone() {
        return "+38050" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    /**
     * Seeds an unused registration ticket for {@code phoneE164} (purpose REGISTRATION, B514) valid 15 min and returns
     * the raw ticket to put in the accept body. Only its SHA-256 hex is stored, exactly as the service stores it.
     */
    public static String seedRegistrationTicket(JdbcTemplate jdbc, String phoneE164) {
        String raw = "tkt" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO phone_registration_tickets (id, ticket_hash, phone, expires_at, used) "
                        + "VALUES (?, ?, ?, ?, false)",
                UUID.randomUUID(), sha256Hex(raw), phoneE164,
                Timestamp.from(Instant.now().plus(15, ChronoUnit.MINUTES)));
        return raw;
    }

    /** The raw token of the LAST invite the spied persistence service saw (the link is the only plaintext copy). */
    public static String lastRawInviteToken(InvitePersistenceService spy) {
        ArgumentCaptor<InvitePersistenceService.PhoneInvite> captor =
                ArgumentCaptor.forClass(InvitePersistenceService.PhoneInvite.class);
        verify(spy, atLeastOnce()).persistInviteAndEnqueue(captor.capture());
        List<InvitePersistenceService.PhoneInvite> invites = captor.getAllValues();
        String link = invites.get(invites.size() - 1).inviteLink();
        return URLDecoder.decode(link.substring(link.indexOf("token=") + "token=".length()), StandardCharsets.UTF_8);
    }

    /** {@code POST /auth/invite/accept} body for a phone invite. */
    public static Map<String, Object> acceptBody(String rawToken, String ticket, String password, String firstName,
                                                 String lastName) {
        return Map.of("token", rawToken, "registrationTicket", ticket, "password", password,
                "firstName", firstName, "lastName", lastName);
    }

    /** Seeds a live phone invite row directly (no SMS) and returns its raw token. */
    public static String seedPhoneInvite(JdbcTemplate jdbc, UUID salonId, String role, String phoneE164,
                                         Instant expiresAt) {
        String raw = "raw-" + UUID.randomUUID();
        jdbc.update("INSERT INTO invite_tokens (id, token, phone, salon_id, role, expires_at, is_used, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, ?, ?, false, NOW(), NOW())",
                UUID.randomUUID(), sha256Hex(raw), phoneE164, salonId, role, Timestamp.from(expiresAt));
        return raw;
    }

    public static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

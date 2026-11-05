package com.beautica.notification.telegram.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 433 — {@link ContactProof}: the booking id and proven phone hash travel exactly on {@code LINKED}, the hash is
 * defensively copied both ways, and {@code toString} never prints it.
 */
@DisplayName("ContactProof — LINKED-only booking id + phone hash invariant")
class ContactProofTest {

    @Test
    @DisplayName("a LINKED proof exposes the token's booking and the proven phone hash")
    void should_exposeBookingAndHash_when_linked() {
        UUID bookingId = UUID.randomUUID();
        byte[] hash = {1, 2, 3};

        ContactProof proof = ContactProof.linked(bookingId, hash);

        assertThat(proof.result()).isEqualTo(ProofResult.LINKED);
        assertThat(proof.linkedBookingId()).contains(bookingId);
        assertThat(proof.phoneHash()).containsExactly(1, 2, 3);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProofResult.class, names = "LINKED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every non-LINKED proof carries no booking and no phone hash")
    void should_carryNoBookingOrHash_when_notLinked(ProofResult result) {
        ContactProof proof = ContactProof.of(result);

        assertThat(proof.linkedBookingId()).isEmpty();
        assertThat(proof.phoneHash()).isNull();
    }

    @Test
    @DisplayName("reject a LINKED proof without a booking id")
    void should_reject_when_linkedWithoutBookingId() {
        assertThatThrownBy(() -> ContactProof.linked(null, new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly when the result is LINKED");
    }

    @Test
    @DisplayName("reject a LINKED proof without a phone hash")
    void should_reject_when_linkedWithoutPhoneHash() {
        assertThatThrownBy(() -> ContactProof.linked(UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly when the result is LINKED");
    }

    @Test
    @DisplayName("reject a non-LINKED proof that names a booking")
    void should_reject_when_mismatchCarriesBooking() {
        assertThatThrownBy(() -> new ContactProof(ProofResult.MISMATCH, UUID.randomUUID(), new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly when the result is LINKED");
    }

    @Test
    @DisplayName("reject a null result")
    void should_reject_when_resultNull() {
        assertThatThrownBy(() -> ContactProof.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("result");
    }

    @Test
    @DisplayName("copy the phone hash in and out, so no caller can mutate the proof")
    void should_copyPhoneHash_when_constructedAndRead() {
        byte[] input = {1, 2, 3};
        ContactProof proof = ContactProof.linked(UUID.randomUUID(), input);

        input[0] = 9;
        proof.phoneHash()[1] = 9;

        assertThat(proof.phoneHash()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("toString names the result only — never the booking or the phone hash")
    void should_printResultOnly_when_toStringCalled() {
        UUID bookingId = UUID.randomUUID();

        String printed = ContactProof.linked(bookingId, new byte[]{42}).toString();

        assertThat(printed).isEqualTo("ContactProof[result=LINKED]").doesNotContain(bookingId.toString());
    }
}

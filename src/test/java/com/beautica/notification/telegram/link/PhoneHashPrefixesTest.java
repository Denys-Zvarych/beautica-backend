package com.beautica.notification.telegram.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 409 audit-fix cycle 2 — the backstop's open-phone set stays at 8 bytes per phone. */
@DisplayName("PhoneHashPrefixes — sorted long[] of 8-byte HMAC prefixes (phase 409)")
class PhoneHashPrefixesTest {

    private static final int BUDGET_PHONES = 1_000_000;
    private static final long TEN_MB = 10L * 1024 * 1024;

    @Test
    @DisplayName("1M phones retain ≤ 10 MB (8 bytes each) and every added hash is found")
    void should_stayWithinTenMegabytes_when_holdingOneMillionPhones() {
        PhoneHashPrefixes prefixes = new PhoneHashPrefixes();
        Random random = new Random(409);
        byte[] probe = null;
        for (int i = 0; i < BUDGET_PHONES; i++) {
            byte[] hash = new byte[32];
            random.nextBytes(hash);
            prefixes.add(hash);
            probe = i == BUDGET_PHONES / 2 ? hash : probe;
        }

        boolean found = prefixes.contains(probe);

        assertThat(found).isTrue();
        assertThat(prefixes.size()).isEqualTo(BUDGET_PHONES);
        assertThat(prefixes.retainedBytes()).isEqualTo((long) BUDGET_PHONES * Long.BYTES).isLessThanOrEqualTo(TEN_MB);
    }

    @Test
    @DisplayName("an unknown hash is absent; a hash sharing only the 8-byte prefix is treated as present (spared)")
    void should_matchOnPrefix_when_lookingUp() {
        PhoneHashPrefixes prefixes = new PhoneHashPrefixes();
        byte[] known = ByteBuffer.allocate(32).putLong(42L).putLong(1L).array();
        byte[] samePrefix = ByteBuffer.allocate(32).putLong(42L).putLong(2L).array();
        byte[] unknown = ByteBuffer.allocate(32).putLong(43L).array();
        prefixes.add(known);

        assertThat(prefixes.contains(samePrefix)).isTrue();
        assertThat(prefixes.contains(unknown)).isFalse();
    }

    @Test
    @DisplayName("adding after the first lookup is refused (the array is sorted once)")
    void should_refuseAdd_when_sealed() {
        PhoneHashPrefixes prefixes = new PhoneHashPrefixes();
        prefixes.contains(new byte[32]);

        assertThatThrownBy(() -> prefixes.add(new byte[32])).isInstanceOf(IllegalStateException.class);
    }
}

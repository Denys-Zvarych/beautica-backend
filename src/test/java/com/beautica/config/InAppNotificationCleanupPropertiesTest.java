package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fail-fast validation for {@link InAppNotificationCleanupProperties} (phase 335). */
@DisplayName("InAppNotificationCleanupProperties — fail-fast validation")
class InAppNotificationCleanupPropertiesTest {

    @Test
    @DisplayName("zero batchSize is rejected")
    void should_rejectBatchSize_when_zero() {
        assertThatThrownBy(() -> new InAppNotificationCleanupProperties(true, 0, 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch-size");
    }

    @Test
    @DisplayName("negative batchSize is rejected")
    void should_rejectBatchSize_when_negative() {
        assertThatThrownBy(() -> new InAppNotificationCleanupProperties(true, -1, 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch-size");
    }

    @Test
    @DisplayName("zero maxBatchesPerRun is rejected")
    void should_rejectMaxBatchesPerRun_when_zero() {
        assertThatThrownBy(() -> new InAppNotificationCleanupProperties(true, 1000, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-batches-per-run");
    }

    @Test
    @DisplayName("negative maxBatchesPerRun is rejected")
    void should_rejectMaxBatchesPerRun_when_negative() {
        assertThatThrownBy(() -> new InAppNotificationCleanupProperties(true, 1000, -1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-batches-per-run");
    }

    @Test
    @DisplayName("the production defaults (enabled=true, batchSize=1000, maxBatchesPerRun=50) are accepted")
    void should_accept_when_valuesAreProductionDefaults() {
        assertThatCode(() -> new InAppNotificationCleanupProperties(true, 1000, 50))
                .doesNotThrowAnyException();
    }

    // ── upper bounds (audit-fix cycle 1, finding 3) ─────────────────────────

    @Test
    @DisplayName("batchSize above 10_000 is rejected")
    void should_rejectBatchSize_when_aboveCeiling() {
        assertThatThrownBy(() -> new InAppNotificationCleanupProperties(true, 10_001, 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch-size");
    }

    @Test
    @DisplayName("batchSize exactly at the 10_000 ceiling is accepted")
    void should_accept_when_batchSizeIsExactlyAtCeiling() {
        assertThatCode(() -> new InAppNotificationCleanupProperties(true, 10_000, 50))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("maxBatchesPerRun above 500 is rejected")
    void should_rejectMaxBatchesPerRun_when_aboveCeiling() {
        assertThatThrownBy(() -> new InAppNotificationCleanupProperties(true, 1000, 501))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-batches-per-run");
    }

    @Test
    @DisplayName("maxBatchesPerRun exactly at the 500 ceiling is accepted")
    void should_accept_when_maxBatchesPerRunIsExactlyAtCeiling() {
        assertThatCode(() -> new InAppNotificationCleanupProperties(true, 1000, 500))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enabled=false is a valid, independent knob")
    void should_accept_when_disabled() {
        assertThatCode(() -> new InAppNotificationCleanupProperties(false, 1000, 50))
                .doesNotThrowAnyException();
    }
}

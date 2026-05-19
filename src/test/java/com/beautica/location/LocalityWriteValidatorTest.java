package com.beautica.location;

import com.beautica.common.exception.BusinessException;
import com.beautica.location.LocalityTaxonomyLookup.LocalityFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the Phase 10.6 most-specific-node write rule and the
 * per-role locality model. Pure JUnit 5 + Mockito (§M: a slice/unit test is
 * the right tool — the rule is logic over the resolved taxonomy facts, no HTTP
 * or DB needed).
 *
 * <p>Phase 10.6 perf fix: the validator no longer calls the city/district
 * repositories directly — it consumes a single cached {@link LocalityFacts}
 * resolution from {@link LocalityTaxonomyLookup}. These tests therefore stub
 * the lookup, but every assertion on the {@link BusinessException}
 * type/message and the branch condition that triggers it is unchanged from
 * the pre-fix suite (the semantics are byte-identical; only the collaborator
 * moved). The "no taxonomy query when short-circuited" expectation is
 * preserved as {@code verify(taxonomyLookup, never()).resolve(...)}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LocalityWriteValidator — unit")
class LocalityWriteValidatorTest {

    @Mock
    private LocalityTaxonomyLookup taxonomyLookup;

    @InjectMocks
    private LocalityWriteValidator validator;

    // ── provider: city mandatory ──────────────────────────────────────────────

    @Test
    @DisplayName("provider — rejects when city_id is absent (AC 1)")
    void should_rejectProvider_when_cityMissing() {
        assertThatThrownBy(() -> validator.validateProviderLocality(LocalityWriteInput.of(null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("City is required");

        verify(taxonomyLookup, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("provider — rejects when city_id does not exist")
    void should_rejectProvider_when_cityUnknown() {
        UUID cityId = UUID.randomUUID();
        when(taxonomyLookup.resolve(cityId, null))
                .thenReturn(new LocalityFacts(false, false, false));

        assertThatThrownBy(() -> validator.validateProviderLocality(LocalityWriteInput.of(cityId, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Selected city does not exist");
    }

    // ── provider: district-required-iff-city-has-districts ────────────────────

    @Test
    @DisplayName("provider — rejects when city has districts but district_id is absent (AC 2)")
    void should_rejectProvider_when_districtRequiredButMissing() {
        UUID cityId = UUID.randomUUID();
        when(taxonomyLookup.resolve(cityId, null))
                .thenReturn(new LocalityFacts(true, true, false));

        assertThatThrownBy(() -> validator.validateProviderLocality(LocalityWriteInput.of(cityId, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("District is required for the selected city");
    }

    @Test
    @DisplayName("provider — succeeds when city has no districts and district_id is null (AC 2)")
    void should_acceptProvider_when_cityHasNoDistricts() {
        UUID cityId = UUID.randomUUID();
        when(taxonomyLookup.resolve(cityId, null))
                .thenReturn(new LocalityFacts(true, false, false));

        assertThatCode(() -> validator.validateProviderLocality(LocalityWriteInput.of(cityId, null)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("provider — rejects a district on a city that defines none")
    void should_rejectProvider_when_districtSuppliedButCityHasNone() {
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        when(taxonomyLookup.resolve(cityId, districtId))
                .thenReturn(new LocalityFacts(true, false, false));

        assertThatThrownBy(() -> validator.validateProviderLocality(LocalityWriteInput.of(cityId, districtId)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("The selected city has no districts; district must be omitted");
    }

    @Test
    @DisplayName("provider — rejects when district is not a child of the supplied city (AC 3)")
    void should_rejectProvider_when_districtNotChildOfCity() {
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        when(taxonomyLookup.resolve(cityId, districtId))
                .thenReturn(new LocalityFacts(true, true, false));

        assertThatThrownBy(() -> validator.validateProviderLocality(LocalityWriteInput.of(cityId, districtId)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Selected district does not belong to the selected city");
    }

    @Test
    @DisplayName("provider — succeeds when district is a valid child of the city")
    void should_acceptProvider_when_districtIsChildOfCity() {
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        when(taxonomyLookup.resolve(cityId, districtId))
                .thenReturn(new LocalityFacts(true, true, true));

        assertThatCode(() -> validator.validateProviderLocality(LocalityWriteInput.of(cityId, districtId)))
                .doesNotThrowAnyException();
    }

    // ── client: optional, never blocks (AC 4) ─────────────────────────────────

    @Test
    @DisplayName("client — accepts a fully-null input (never blocks the save / OTP registration)")
    void should_acceptClient_when_noLocality() {
        assertThatCode(() -> validator.validateClientLocality(LocalityWriteInput.of(null, null)))
                .doesNotThrowAnyException();

        verify(taxonomyLookup, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("client — rejects a district supplied without a city")
    void should_rejectClient_when_districtWithoutCity() {
        assertThatThrownBy(() -> validator.validateClientLocality(
                LocalityWriteInput.of(null, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .hasMessage("District cannot be set without a city");

        verify(taxonomyLookup, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("client — accepts a valid city alone (district not required for the discovery default)")
    void should_acceptClient_when_cityOnly() {
        UUID cityId = UUID.randomUUID();
        // A city that HAS districts: the client path must still accept a
        // city-only locality (it intentionally ignores cityHasDistricts —
        // the discovery default never requires a district).
        when(taxonomyLookup.resolve(cityId, null))
                .thenReturn(new LocalityFacts(true, true, false));

        assertThatCode(() -> validator.validateClientLocality(LocalityWriteInput.of(cityId, null)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("client — rejects an unknown city when one is supplied")
    void should_rejectClient_when_cityUnknown() {
        UUID cityId = UUID.randomUUID();
        when(taxonomyLookup.resolve(cityId, null))
                .thenReturn(new LocalityFacts(false, false, false));

        assertThatThrownBy(() -> validator.validateClientLocality(LocalityWriteInput.of(cityId, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Selected city does not exist");
    }

    @Test
    @DisplayName("client — rejects a district that is not a child of the supplied city (AC 3)")
    void should_rejectClient_when_districtNotChildOfCity() {
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        when(taxonomyLookup.resolve(cityId, districtId))
                .thenReturn(new LocalityFacts(true, true, false));

        assertThatThrownBy(() -> validator.validateClientLocality(LocalityWriteInput.of(cityId, districtId)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Selected district does not belong to the selected city");
    }

    @Test
    @DisplayName("error shape — message carries no enum/SQL/ID leak (§I/§N)")
    void should_keepMessagesGeneric_when_rejecting() {
        var ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> validator.validateProviderLocality(LocalityWriteInput.of(null, null)));

        assertThat(ex.getMessage()).doesNotContain("CLIENT", "INDEPENDENT_MASTER", "SELECT", "UUID", "city_id");
    }
}

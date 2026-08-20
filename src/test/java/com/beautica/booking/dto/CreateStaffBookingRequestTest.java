package com.beautica.booking.dto;

import com.beautica.booking.service.SlotCalculationService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation unit guards for {@link CreateStaffBookingRequest} (Phase 22.11) — the
 * {@code masterServiceId} scalar → {@code masterServiceIds} ordered-list widening. Mirrors
 * {@link CreateAppointmentRequest}'s own {@code @NotEmpty}/{@code @Size} cap byte-for-byte, per the
 * phase doc's D3.
 */
@DisplayName("CreateStaffBookingRequest — masterServiceIds bean validation (Phase 22.11)")
class CreateStaffBookingRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private static final GuestClientDto VALID_GUEST =
            new GuestClientDto("Марія", "Левченко", "050 123 45 67");

    private static OffsetDateTime future() {
        return OffsetDateTime.now().plusDays(1);
    }

    private static CreateStaffBookingRequest request(List<UUID> masterServiceIds) {
        return new CreateStaffBookingRequest(masterServiceIds, future(), VALID_GUEST);
    }

    private static List<UUID> randomIds(int n) {
        return Stream.generate(UUID::randomUUID).limit(n).collect(Collectors.toList());
    }

    @Test
    @DisplayName("should_reject_when_masterServiceIdsEmpty")
    void should_reject_when_masterServiceIdsEmpty() {
        Set<ConstraintViolation<CreateStaffBookingRequest>> violations =
                validator.validate(request(List.of()));

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("masterServiceIds");
    }

    @Test
    @DisplayName("should_reject_when_masterServiceIdsNull")
    void should_reject_when_masterServiceIdsNull() {
        Set<ConstraintViolation<CreateStaffBookingRequest>> violations =
                validator.validate(request(null));

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("masterServiceIds");
    }

    @Test
    @DisplayName("should_reject_when_masterServiceIdsExceedsTen")
    void should_reject_when_masterServiceIdsExceedsTen() {
        Set<ConstraintViolation<CreateStaffBookingRequest>> violations =
                validator.validate(request(randomIds(SlotCalculationService.MAX_SERVICES_PER_VISIT + 1)));

        assertThat(violations)
                .as("the message must name the cap, not a generic size failure")
                .anySatisfy(v -> assertThat(v.getMessage())
                        .contains(String.valueOf(SlotCalculationService.MAX_SERVICES_PER_VISIT)));
    }

    @Test
    @DisplayName("should_accept_when_exactlyTenServices")
    void should_accept_when_exactlyTenServices() {
        Set<ConstraintViolation<CreateStaffBookingRequest>> violations =
                validator.validate(request(randomIds(SlotCalculationService.MAX_SERVICES_PER_VISIT)));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("should_accept_when_duplicateServiceIds")
    void should_accept_when_duplicateServiceIds() {
        UUID id = UUID.randomUUID();

        Set<ConstraintViolation<CreateStaffBookingRequest>> violations =
                validator.validate(request(List.of(id, id, id)));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("should_accept_when_singleService")
    void should_accept_when_singleService() {
        Set<ConstraintViolation<CreateStaffBookingRequest>> violations =
                validator.validate(request(List.of(UUID.randomUUID())));

        assertThat(violations).isEmpty();
    }

    /**
     * {@code toCommand} must pass the list through VERBATIM — order is the performance order.
     * Mutation-check RED by inserting a {@code .sorted()} into {@code toCommand}: a reversed input
     * order would then arrive sorted (ascending by {@link UUID#compareTo}), which — for two
     * arbitrary random UUIDs — is observably different from the reversed order asserted here in the
     * overwhelming majority of runs.
     */
    @Test
    @DisplayName("should_preserveOrder_when_mappedToCommand")
    void should_preserveOrder_when_mappedToCommand() {
        List<UUID> reversed = new ArrayList<>(randomIds(4));
        UUID masterId = UUID.randomUUID();
        StaffBookingScope scope = new StaffBookingScope.InSalon(UUID.randomUUID());
        CreateStaffBookingRequest req = request(reversed);

        StaffBookingCommand cmd = req.toCommand(masterId, scope);

        assertThat(cmd.masterServiceIds()).containsExactlyElementsOf(reversed);
    }
}

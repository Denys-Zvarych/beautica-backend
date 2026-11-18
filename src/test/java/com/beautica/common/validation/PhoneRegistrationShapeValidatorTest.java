package com.beautica.common.validation;

import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("PhoneRegistrationShapeValidator — ticket vs legacy register shape (B514, AUTH-02 / AUTH-43)")
class PhoneRegistrationShapeValidatorTest {

    private static final String PASSWORD = "Str0ng!Passw0rd#";

    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        context = Mockito.mock(ConstraintValidatorContext.class, Answers.RETURNS_DEEP_STUBS);
    }

    private static RegisterRequest phoneShape(String email) {
        return new RegisterRequest(email, PASSWORD, SelfRegistrationRole.CLIENT, "Olena", null, null, null,
                null, null, null, "ticket-abc");
    }

    private static RegisterRequest legacyShape(String email, String lastName, String phone) {
        return new RegisterRequest(email, PASSWORD, SelfRegistrationRole.CLIENT, "Olena", lastName, phone, null);
    }

    @Test
    @DisplayName("should_accept_when_ticketWithoutEmailPhoneOrLastName")
    void should_accept_when_ticketWithoutEmailPhoneOrLastName() {
        boolean valid = new PhoneRegistrationShapeValidator(true).isValid(phoneShape(null), context);

        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("should_reject_when_ticketAndEmailBothSent")
    void should_reject_when_ticketAndEmailBothSent() {
        boolean valid = new PhoneRegistrationShapeValidator(false).isValid(phoneShape("a@b.ua"), context);

        assertThat(valid).isFalse();
        verify(context).buildConstraintViolationWithTemplate(PhoneRegistrationShapeValidator.MSG_EMAIL_WITH_TICKET);
    }

    @Test
    @DisplayName("should_acceptLegacyShape_when_flagOffAndAllLegacyFieldsPresent")
    void should_acceptLegacyShape_when_flagOffAndAllLegacyFieldsPresent() {
        boolean valid = new PhoneRegistrationShapeValidator(false)
                .isValid(legacyShape("a@b.ua", "Koval", "+380501234567"), context);

        assertThat(valid).isTrue();
        verify(context, never()).buildConstraintViolationWithTemplate(anyString());
    }

    @Test
    @DisplayName("should_reportEachMissingLegacyField_when_flagOff")
    void should_reportEachMissingLegacyField_when_flagOff() {
        boolean valid = new PhoneRegistrationShapeValidator(false).isValid(legacyShape(" ", null, null), context);

        assertThat(valid).isFalse();
        verify(context).buildConstraintViolationWithTemplate(PhoneRegistrationShapeValidator.MSG_EMAIL_REQUIRED);
        verify(context).buildConstraintViolationWithTemplate(PhoneRegistrationShapeValidator.MSG_LAST_NAME_REQUIRED);
        verify(context).buildConstraintViolationWithTemplate(PhoneRegistrationShapeValidator.MSG_PHONE_REQUIRED);
    }

    @Test
    @DisplayName("should_rejectLegacyShape_when_requirePhoneRegistrationOn")
    void should_rejectLegacyShape_when_requirePhoneRegistrationOn() {
        boolean valid = new PhoneRegistrationShapeValidator(true)
                .isValid(legacyShape("a@b.ua", "Koval", "+380501234567"), context);

        assertThat(valid).isFalse();
        verify(context).buildConstraintViolationWithTemplate(PhoneRegistrationShapeValidator.MSG_TICKET_REQUIRED);
    }

    @Test
    @DisplayName("should_requireOnlyPhone_when_legacyInviteAccept")
    void should_requireOnlyPhone_when_legacyInviteAccept() {
        InviteAcceptRequest request = new InviteAcceptRequest("tok", PASSWORD, "Olena", "Koval", null);

        boolean valid = new PhoneRegistrationShapeValidator(false).isValid(request, context);

        assertThat(valid).isFalse();
        verify(context).buildConstraintViolationWithTemplate(PhoneRegistrationShapeValidator.MSG_PHONE_REQUIRED);
        verify(context, never()).buildConstraintViolationWithTemplate(
                PhoneRegistrationShapeValidator.MSG_EMAIL_REQUIRED);
    }
}

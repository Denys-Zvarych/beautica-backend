package com.beautica.user.phone;

import com.beautica.auth.Role;
import com.beautica.config.AuthConsentProperties;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VerifiedPhoneGateInterceptor — the hot path never reaches the gate when nothing can be gated (B516)")
class VerifiedPhoneGateInterceptorTest {

    @Mock private UserRepository userRepository;
    @Mock private ObjectProvider<VerifiedPhoneGate> provider;

    private final AuthConsentProperties properties = new AuthConsentProperties();
    private VerifiedPhoneGate gate;
    private VerifiedPhoneGateInterceptor interceptor;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
        gate = spy(new VerifiedPhoneGate(userRepository, new PhoneRequirementPolicy(properties, clock), properties));
        interceptor = new VerifiedPhoneGateInterceptor(provider);
        SecurityContextHolder.getContext().setAuthentication(VerifiedPhoneGateTest.token(Role.CLIENT));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** A handler method annotated with {@link RequireVerifiedPhone} (a booking create). */
    @RequireVerifiedPhone
    public void bookingCreate() {
        // handler stand-in
    }

    /** A plain handler method. */
    public void plainHandler() {
        // handler stand-in
    }

    private HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(this, getClass().getMethod(name));
    }

    @Test
    @DisplayName("with the flag off a booking create passes without calling the gate's check or the repository")
    void should_skipGate_when_flagOff() throws Exception {
        when(provider.getIfAvailable()).thenReturn(gate);
        var request = new MockHttpServletRequest("POST", "/api/v1/bookings");

        boolean proceed = interceptor.preHandle(request, new MockHttpServletResponse(), handler("bookingCreate"));

        assertThat(proceed).isTrue();
        verify(gate, never()).check(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("a safe read that is not a booking create never even resolves the gate")
    void should_notResolveGate_when_plainRead() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/salons/x");

        boolean proceed = interceptor.preHandle(request, new MockHttpServletResponse(), handler("plainHandler"));

        assertThat(proceed).isTrue();
        verifyNoInteractions(provider, userRepository);
    }

    @Test
    @DisplayName("with the flag on a booking create reaches the gate")
    void should_callGate_when_flagOnAndBookingCreate() throws Exception {
        properties.setRequireVerifiedPhone(true);
        when(provider.getIfAvailable()).thenReturn(gate);
        var request = new MockHttpServletRequest("POST", "/api/v1/bookings");

        interceptor.preHandle(request, new MockHttpServletResponse(), handler("bookingCreate"));

        verify(userRepository).findPhoneGateRow(org.mockito.ArgumentMatchers.any());
    }
}

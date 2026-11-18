package com.beautica.user.phone;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * B516 (AUTH-45) — runs {@link VerifiedPhoneGate#check} before a gated controller handler. Short-circuits before the
 * gate is even consulted for a safe read that is not a booking create, and when the flag is off — no DB read, no
 * connection borrowed on the hot path. Resolved lazily (as
 * {@code AppCheckInterceptor}) so {@code @WebMvcTest} slices without the gate bean stay bootable: no bean → no gate.
 * A rejection is rendered by {@code GlobalExceptionHandler} like any controller exception.
 */
class VerifiedPhoneGateInterceptor implements HandlerInterceptor {

    private final ObjectProvider<VerifiedPhoneGate> gate;

    VerifiedPhoneGateInterceptor(ObjectProvider<VerifiedPhoneGate> gate) {
        this.gate = gate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        boolean bookingCreate = method.hasMethodAnnotation(RequireVerifiedPhone.class);
        boolean write = VerifiedPhoneGate.isWrite(request.getMethod());
        if (!bookingCreate && !write) {
            return true;
        }
        VerifiedPhoneGate resolved = gate.getIfAvailable();
        if (resolved == null || !resolved.isActive()) {
            return true;
        }
        resolved.check(SecurityContextHolder.getContext().getAuthentication(), bookingCreate, request.getMethod(),
                request.getRequestURI().substring(request.getContextPath().length()));
        return true;
    }
}

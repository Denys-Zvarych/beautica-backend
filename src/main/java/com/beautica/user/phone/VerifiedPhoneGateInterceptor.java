package com.beautica.user.phone;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * B516 (AUTH-45) — runs {@link VerifiedPhoneGate#check} before a gated controller handler. Short-circuits before the
 * gate is even consulted for a safe read that is not a booking create, and when the flag is off — no DB read, no
 * connection borrowed on the hot path. Resolved lazily (as
 * {@code AppCheckInterceptor}) so {@code @WebMvcTest} slices without the gate bean stay bootable: no bean → no gate.
 * A rejection is rendered by {@code GlobalExceptionHandler} like any controller exception.
 */
class VerifiedPhoneGateInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of(
            HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name());

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
        boolean write = !SAFE_METHODS.contains(request.getMethod());
        if (!bookingCreate && !write) {
            return true;
        }
        VerifiedPhoneGate resolved = gate.getIfAvailable();
        if (resolved == null || !resolved.isActive()) {
            return true;
        }
        resolved.check(SecurityContextHolder.getContext().getAuthentication(), bookingCreate, write,
                request.getRequestURI().substring(request.getContextPath().length()));
        return true;
    }
}

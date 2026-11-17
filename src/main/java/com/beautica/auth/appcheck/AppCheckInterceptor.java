package com.beautica.auth.appcheck;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * B514 — runs {@link AppCheckGuard#check} before every handler annotated {@link RequireAppCheck}. A rejection
 * thrown here is rendered by {@code GlobalExceptionHandler} like any controller exception. The guard is resolved
 * lazily so {@code @WebMvcTest} slices (which load every {@code WebMvcConfigurer} but not the guard) stay bootable;
 * an annotated handler with no guard bean fails closed ({@code getObject} throws).
 */
class AppCheckInterceptor implements HandlerInterceptor {

    private final ObjectProvider<AppCheckGuard> guard;

    AppCheckInterceptor(ObjectProvider<AppCheckGuard> guard) {
        this.guard = guard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method && method.hasMethodAnnotation(RequireAppCheck.class)) {
            guard.getObject().check(request);
        }
        return true;
    }
}

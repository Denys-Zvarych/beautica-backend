package com.beautica.common.web;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * 200 responses whose body depends on WHO is asking (e.g. the per-client {@code isFavorite} on
 * the public master/salon profiles and search cards), on routes that anonymous callers share.
 *
 * <p>{@code Cache-Control: no-store} keeps any browser, proxy or CDN from storing one viewer's
 * body, and {@code Vary: Authorization} tells any shared cache that ignores {@code no-store} that
 * the body differs per bearer token. Set explicitly on the handler rather than relied on from
 * Spring Security's default header writer, which a future {@code headers(...)} change could turn
 * off without anyone noticing these routes became per-viewer.
 */
public final class PerViewerResponses {

    private PerViewerResponses() {
    }

    public static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .varyBy(HttpHeaders.AUTHORIZATION)
                .body(body);
    }
}

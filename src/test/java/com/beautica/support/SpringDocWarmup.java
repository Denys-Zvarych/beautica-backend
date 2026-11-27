package com.beautica.support;

import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Locale;

/**
 * Builds the SpringDoc model in-process before an IT fetches {@code /api-docs} over HTTP.
 *
 * <p>The cold SpringDoc model build takes ~9-10 s under load, which races the base class's 10 s HTTP
 * read timeout ({@code TestHttpClients.TIMEOUT_MILLIS}). Building it in-process first (no socket,
 * no timeout) fills SpringDoc's per-locale cache, so the HTTP GET that follows only serialises it.
 * The locale matches what the server resolves for a request with no {@code Accept-Language}.
 */
public final class SpringDocWarmup {

    private static final String API_DOCS_PATH = "/api-docs";

    private SpringDocWarmup() {
    }

    public static void warm(OpenApiWebMvcResource openApiResource) throws Exception {
        openApiResource.openapiJson(new MockHttpServletRequest(), API_DOCS_PATH, Locale.getDefault());
    }
}

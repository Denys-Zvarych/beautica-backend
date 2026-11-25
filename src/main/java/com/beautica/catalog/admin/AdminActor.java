package com.beautica.catalog.admin;

import com.beautica.catalog.admin.entity.CatalogAdminAudit;
import com.beautica.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.regex.Pattern;

/**
 * The catalog-admin actor (B501): the Cloudflare Access e-mail the website Worker (W039) forwards in
 * {@value #HEADER}. Trusted ONLY because {@code InternalApiKeyFilter} already authenticated the caller with the
 * internal or catalog key; the header itself proves nothing. Catalog writes (B502–B505) take it as
 * {@code @RequestHeader(AdminActor.HEADER) String} and call {@link #of} — a missing, blank, over-long or non-email
 * value is a 400, never a 500. The value is never echoed in the error message.
 */
public record AdminActor(String email) {

    public static final String HEADER = "X-Admin-Actor";

    /** Same shape as {@code ReportedContent}'s e-mail rule: no whitespace, quotes or control characters. */
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._+-]{1,64}@[A-Za-z0-9.-]{1,185}\\.[A-Za-z]{2,}$");

    public AdminActor {
        if (!isValid(email)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Invalid " + HEADER + " header");
        }
    }

    /** Parses the raw header value; surrounding whitespace is trimmed. */
    public static AdminActor of(String headerValue) {
        return new AdminActor(headerValue == null ? null : headerValue.strip());
    }

    static boolean isValid(String email) {
        return email != null
                && email.length() <= CatalogAdminAudit.ACTOR_MAX_LENGTH
                && EMAIL.matcher(email).matches();
    }
}

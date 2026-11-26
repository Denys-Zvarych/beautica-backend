package com.beautica.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;

/**
 * B502 (SVC-55) — a catalog-admin edit of a pending category request / service-type suggestion was refused; since B503
 * (SVC-06) also a portal approve / reject of a row that is no longer PENDING ({@link Code#REQUEST_NOT_PENDING}); since
 * B504 (SVC-56) also a refused rename / hide / unhide of a live category or service type
 * ({@link Code#TYPE_EXISTS}, {@link Code#CATEGORY_NAME_EXISTS}, {@link Code#CATEGORY_NOT_APPROVED}); since B505
 * (SVC-57) also a refused delete of a category or service type that something still references
 * ({@link Code#CATALOG_IN_USE}, with the {@link CatalogUsage} counts next to the code), and a suggestion submit that
 * timed out waiting for its category's row lock ({@link Code#CATEGORY_BUSY}, retry-able).
 *
 * <p>Surfaces as {@code 409 Conflict} carrying the stable {@link Code} under {@code data.code} (the project's typed
 * error-code convention, same shape as {@link OwnerAccountDeletionBlockedException}):
 * {@code {"success":false,"data":{"code":"REQUEST_NOT_PENDING"},"message":"…","requestId":"…"}}.
 * {@link GlobalExceptionHandler#handleBusiness} would genericise the body to {@code data: null}, so the website Worker
 * (W040) could not tell the cases apart. The message is fixed per code in the handler, never {@code getMessage()}.
 * Stack-trace capture is suppressed (flow control).
 */
public class CatalogRequestConflictException extends BusinessException {

    /** Stable error codes echoed under {@code data.code}; the portal maps them to its own copy. */
    public enum Code {
        /** The request was already approved or rejected. */
        REQUEST_NOT_PENDING,
        /** Another PENDING suggestion in the same category has the same name (case-insensitive, V197 index). */
        DUPLICATE_PENDING,
        /**
         * An active service type with this name already exists in the category. B504: also a type rename onto another
         * type's name (active or hidden) in the same category, or an unhide onto an active type's name.
         */
        TYPE_EXISTS,
        /** B504 — another approved or pending category already has this display name (case-insensitive). */
        CATEGORY_NAME_EXISTS,
        /** B504 — the category is a pending or rejected request, not a live category; edit it via the request queue. */
        CATEGORY_NOT_APPROVED,
        /**
         * B505 — the category / type is still referenced (service types, service definitions active or inactive,
         * PENDING suggestions); the operator hides it instead (SVC-56). Carries the counts, except when the FK
         * backstop fired (then the code alone).
         */
        CATALOG_IN_USE,
        /**
         * B505 — a provider's service-type suggestion waited longer than the 3 s {@code lock_timeout} for its
         * category's row (held by a portal rename / hide / decision / delete on the same category). Nothing was
         * written; retrying shortly succeeds.
         */
        CATEGORY_BUSY,
        /** B539 — an icon PUT on a REJECTED category request; only PENDING and APPROVED categories take an icon. */
        CATEGORY_REJECTED,
        /**
         * B541 (SVC-60) — approve, unhide or internal create of a category that has no icon, while
         * {@code app.catalog.icon-required} is on. Upload one ({@code PUT …/categories/{name}/icon}) and retry.
         */
        CATEGORY_ICON_REQUIRED
    }

    private final Code code;
    @Nullable
    private final transient CatalogUsage usage;
    @Nullable
    private final String wireMessage;

    public CatalogRequestConflictException(Code code) {
        this(code, null);
    }

    /** B505 — {@code usage} is the reason for a {@link Code#CATALOG_IN_USE}; {@code null} for every other code. */
    public CatalogRequestConflictException(Code code, @Nullable CatalogUsage usage) {
        this(code, usage, "Catalog request edit refused: " + code.name(), null);
    }

    /**
     * B505 — a typed 409 for a path that already had an UNTYPED one: {@code message} keeps the exception's existing
     * {@link #getMessage()} and {@code wireMessage} keeps the response {@code message} byte-identical; only
     * {@code data} gains the code.
     *
     * <p>Both must be fixed constants, never caller input: {@code message} is logged at WARN by the 409 handler.
     * {@code message} is the LAST parameter on purpose so {@code BusinessExceptionMessageGuardTest}'s
     * {@code PASS_THROUGH_HELPERS} entry checks every caller passes a constant there. Caller text belongs in
     * {@link BusinessException#withUserInput}, not here.
     */
    public static CatalogRequestConflictException withFixedMessages(Code code, String wireMessage, String message) {
        return new CatalogRequestConflictException(code, null, message, wireMessage);
    }

    private CatalogRequestConflictException(Code code, @Nullable CatalogUsage usage, String message,
                                            @Nullable String wireMessage) {
        super(HttpStatus.CONFLICT, message);
        this.code = code;
        this.usage = usage;
        this.wireMessage = wireMessage;
    }

    public Code getCode() {
        return code;
    }

    @Nullable
    public CatalogUsage getUsage() {
        return usage;
    }

    /** The response {@code message} override; {@code null} = the handler's fixed per-code text. */
    @Nullable
    public String getWireMessage() {
        return wireMessage;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}

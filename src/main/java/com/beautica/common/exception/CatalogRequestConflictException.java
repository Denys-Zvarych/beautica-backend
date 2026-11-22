package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

/**
 * B502 (SVC-55) — a catalog-admin edit of a pending category request / service-type suggestion was refused; since B503
 * (SVC-06) also a portal approve / reject of a row that is no longer PENDING ({@link Code#REQUEST_NOT_PENDING}); since
 * B504 (SVC-56) also a refused rename / hide / unhide of a live category or service type
 * ({@link Code#TYPE_EXISTS}, {@link Code#CATEGORY_NAME_EXISTS}, {@link Code#CATEGORY_NOT_APPROVED}).
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
        CATEGORY_NOT_APPROVED
    }

    private final Code code;

    public CatalogRequestConflictException(Code code) {
        super(HttpStatus.CONFLICT, "Catalog request edit refused: " + code.name());
        this.code = code;
    }

    public Code getCode() {
        return code;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}

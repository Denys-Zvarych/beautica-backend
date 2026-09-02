package com.beautica.auth.dto;

/**
 * Response body for a rejected invite-token operation ({@code GET /auth/invite/validate},
 * {@code POST /auth/invite/accept}).
 *
 * <p>Mirrors {@code VerificationErrorResponse} exactly (phase 285): the {@code code} field carries
 * the stable {@code InviteTokenException.Code} discriminator the mobile client branches on (mobile
 * phase 304). The HTTP status is unchanged from before phase 285 for every existing case — see the
 * phase doc's backward-compatibility gate — so an old client that only inspects the status keeps
 * working; this record only adds a previously-absent {@code data} object where old clients saw
 * {@code null}.
 */
public record InviteErrorResponse(String code) {}

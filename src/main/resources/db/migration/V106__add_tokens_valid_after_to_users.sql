-- Security re-audit follow-up on the access-token-denylist work (Finding 1, MEDIUM):
-- PasswordResetService.resetPassword only revoked refresh tokens
-- (refreshTokenRepository.deleteByUserId). An attacker holding a stolen access token
-- kept full API access for up to its remaining TTL even after the legitimate owner
-- reset their password, because access tokens are stateless JWTs verified purely by
-- signature + expiry (see JwtAuthenticationFilter / AccessTokenDenylist).
--
-- tokens_valid_after stamps the instant after which an access token's `iat`
-- (issued-at) claim must fall to be accepted by JwtAuthenticationFilter. NULL means
-- "no reset has ever occurred" (the default, common case) so the vast majority of
-- users never trigger the extra check. No DEFAULT is needed — NULL is the correct
-- "never reset" value for both new rows and existing rows on this additive column.

ALTER TABLE users
    ADD COLUMN tokens_valid_after TIMESTAMPTZ;

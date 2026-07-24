package com.beautica.media.dto;

/**
 * Response DTO returned by avatar upload/delete endpoints.
 *
 * <p>Single field — the public R2 URL. {@code avatarUrl} may be {@code null}
 * after {@link com.beautica.media.service.MediaService#deleteAvatar(java.util.UUID)}.
 */
public record AvatarResponse(String avatarUrl) {
}

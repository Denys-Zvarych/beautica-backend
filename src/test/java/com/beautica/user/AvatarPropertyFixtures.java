package com.beautica.user;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Test-source-only planted request types — the positive controls of
 * {@link RequestDtoAvatarFieldArchitectureTest}. Each smuggles an avatar property past the scan
 * through a different binding route; {@link RequestPropertyNames} must flag every one, and must not
 * flag {@link CleanRequest}. Never on the production classpath (the production scan imports with
 * {@code DO_NOT_INCLUDE_TESTS}).
 */
final class AvatarPropertyFixtures {

    private AvatarPropertyFixtures() {
    }

    /** Renamed binding: the component is {@code pic}, the JSON name is {@code avatarUrl}. */
    record PlantedAvatarRequest(@JsonProperty("avatarUrl") String pic) {
    }

    /** Plain record component named {@code avatarUrl}. */
    record PlainAvatarRequest(String avatarUrl) {
    }

    /** Inherited: the avatar field lives only on the superclass. */
    static class AvatarBase {
        private String avatarUrl;
    }

    static final class InheritedAvatarRequest extends AvatarBase {
        private String firstName;
    }

    /** {@code @JsonCreator} constructor whose parameter binds {@code avatarUrl}; the field is {@code pic}. */
    static final class CreatorAvatarRequest {
        private final String pic;

        @JsonCreator
        CreatorAvatarRequest(@JsonProperty("avatarUrl") String pic) {
            this.pic = pic;
        }
    }

    /** Setter-bound: field {@code pic}, Jackson binds {@code avatarUrl} through {@code setAvatarUrl}. */
    static final class SetterAvatarRequest {
        private String pic;

        public void setAvatarUrl(String value) {
            this.pic = value;
        }
    }

    /** Negative control — nothing avatar-named on any route. */
    record CleanRequest(String firstName, @JsonProperty("last_name") String lastName) {
    }
}

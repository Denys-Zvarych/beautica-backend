package com.beautica.common.security;

import com.beautica.auth.Role;
import com.beautica.common.exception.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AuthenticationUtils} — the centralized principal/role extractor
 * used by 17 controllers. Pure static logic: no Spring context, no Mockito.
 */
class AuthenticationUtilsTest {

    private static UsernamePasswordAuthenticationToken tokenWith(UUID details, GrantedAuthority... authorities) {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken("principal", "credentials", List.of(authorities));
        token.setDetails(details);
        return token;
    }

    private static SimpleGrantedAuthority authority(Role role) {
        return new SimpleGrantedAuthority(role.springRole);
    }

    @Nested
    @DisplayName("userId(Authentication)")
    class UserId {

        @Test
        @DisplayName("userId — returns the UUID stored as token details on a UPAT")
        void should_returnUuid_when_upatCarriesUuidDetails() {
            UUID expected = UUID.randomUUID();
            UsernamePasswordAuthenticationToken token = tokenWith(expected, authority(Role.CLIENT));

            assertThat(AuthenticationUtils.userId(token)).isEqualTo(expected);
        }

        @Test
        @DisplayName("userId — throws ForbiddenException(\"Not authenticated\") when authentication is null")
        void should_throwForbidden_when_authenticationIsNull() {
            assertThatThrownBy(() -> AuthenticationUtils.userId(null))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Not authenticated");
        }

        @Test
        @DisplayName("userId — throws ForbiddenException when principal is not a UPAT (AnonymousAuthenticationToken)")
        void should_throwForbidden_when_principalIsNotUpat() {
            AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                    "key", "anonymousUser",
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

            assertThatThrownBy(() -> AuthenticationUtils.userId(anonymous))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Not authenticated");
        }

        @Test
        @DisplayName("userId — throws ForbiddenException when UPAT details is not a UUID (a String)")
        void should_throwForbidden_when_detailsIsNotUuid() {
            UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken("principal", "credentials",
                            List.of(authority(Role.CLIENT)));
            token.setDetails("not-a-uuid");

            assertThatThrownBy(() -> AuthenticationUtils.userId(token))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Not authenticated");
        }

        @Test
        @DisplayName("userId — throws ForbiddenException when UPAT details is null")
        void should_throwForbidden_when_detailsIsNull() {
            UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken("principal", "credentials",
                            List.of(authority(Role.CLIENT)));

            assertThatThrownBy(() -> AuthenticationUtils.userId(token))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Not authenticated");
        }
    }

    @Nested
    @DisplayName("userIdOrNull(Authentication)")
    class UserIdOrNull {

        @Test
        @DisplayName("userIdOrNull — returns the UUID stored as token details on a UPAT")
        void should_returnUuid_when_upatCarriesUuidDetails() {
            UUID expected = UUID.randomUUID();
            UsernamePasswordAuthenticationToken token = tokenWith(expected, authority(Role.CLIENT));

            assertThat(AuthenticationUtils.userIdOrNull(token)).isEqualTo(expected);
        }

        @Test
        @DisplayName("userIdOrNull — returns null (never throws) when authentication is null")
        void should_returnNull_when_authenticationIsNull() {
            assertThat(AuthenticationUtils.userIdOrNull(null)).isNull();
        }

        @Test
        @DisplayName("userIdOrNull — returns null when principal is not a UPAT (AnonymousAuthenticationToken)")
        void should_returnNull_when_principalIsNotUpat() {
            AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                    "key", "anonymousUser",
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

            assertThat(AuthenticationUtils.userIdOrNull(anonymous)).isNull();
        }

        @Test
        @DisplayName("userIdOrNull — returns null when UPAT details is not a UUID (a String)")
        void should_returnNull_when_detailsIsNotUuid() {
            UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken("principal", "credentials",
                            List.of(authority(Role.CLIENT)));
            token.setDetails("not-a-uuid");

            assertThat(AuthenticationUtils.userIdOrNull(token)).isNull();
        }
    }

    @Nested
    @DisplayName("role(Authentication)")
    class RoleResolution {

        @Test
        @DisplayName("role — returns CLIENT when the single authority is ROLE_CLIENT")
        void should_returnClient_when_singleClientAuthority() {
            UsernamePasswordAuthenticationToken token =
                    tokenWith(UUID.randomUUID(), authority(Role.CLIENT));

            assertThat(AuthenticationUtils.role(token)).isEqualTo(Role.CLIENT);
        }

        @Test
        @DisplayName("role — returns SALON_OWNER when the single authority is ROLE_SALON_OWNER")
        void should_returnSalonOwner_when_singleSalonOwnerAuthority() {
            UsernamePasswordAuthenticationToken token =
                    tokenWith(UUID.randomUUID(), authority(Role.SALON_OWNER));

            assertThat(AuthenticationUtils.role(token)).isEqualTo(Role.SALON_OWNER);
        }

        @Test
        @DisplayName("role — throws ForbiddenException(\"Not authenticated\") when principal is not a UPAT")
        void should_throwForbidden_when_principalIsNotUpat() {
            AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                    "key", "anonymousUser",
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

            assertThatThrownBy(() -> AuthenticationUtils.role(anonymous))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Not authenticated");
        }

        @Test
        @DisplayName("role — throws ForbiddenException(\"No role assigned\") when no ROLE_* authority is present")
        void should_throwNoRole_when_noRoleAuthority() {
            UsernamePasswordAuthenticationToken token =
                    tokenWith(UUID.randomUUID(), new SimpleGrantedAuthority("SCOPE_read"));

            assertThatThrownBy(() -> AuthenticationUtils.role(token))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("No role assigned");
        }

        @Test
        @DisplayName("role — ignores an unrecognized ROLE_* authority, treating it as no-role")
        void should_throwNoRole_when_onlyUnrecognizedRolePrefixAuthority() {
            UsernamePasswordAuthenticationToken token =
                    tokenWith(UUID.randomUUID(), new SimpleGrantedAuthority("ROLE_SUPERHERO"));

            assertThatThrownBy(() -> AuthenticationUtils.role(token))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("No role assigned");
        }

        @Test
        @DisplayName("role — resolves the single valid role when an unrecognized ROLE_* authority is also present")
        void should_returnValidRole_when_unrecognizedRoleAlsoPresent() {
            UsernamePasswordAuthenticationToken token = tokenWith(
                    UUID.randomUUID(),
                    authority(Role.SALON_MASTER),
                    new SimpleGrantedAuthority("ROLE_SUPERHERO"));

            assertThat(AuthenticationUtils.role(token)).isEqualTo(Role.SALON_MASTER);
        }

        @Test
        @DisplayName("role — fails closed with ForbiddenException(\"Ambiguous role\") when two distinct roles are present")
        void should_throwAmbiguous_when_twoDistinctRolesPresent() {
            UsernamePasswordAuthenticationToken token = tokenWith(
                    UUID.randomUUID(),
                    authority(Role.CLIENT),
                    authority(Role.SALON_OWNER));

            assertThatThrownBy(() -> AuthenticationUtils.role(token))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("Ambiguous role");
        }
    }
}

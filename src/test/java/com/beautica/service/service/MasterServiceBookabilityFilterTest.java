package com.beautica.service.service;

import com.beautica.booking.service.BookingMasterService;
import com.beautica.common.security.AuthorizationService;
import com.beautica.service.dto.MasterServiceResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MasterServiceBookabilityFilter — client view of GET /masters/{id}/services")
class MasterServiceBookabilityFilterTest {

    @Mock private BookingMasterService bookingMasterService;
    @Mock private AuthorizationService authorizationService;

    @InjectMocks private MasterServiceBookabilityFilter filter;

    private final UUID masterId = UUID.randomUUID();
    private final UUID bookableId = UUID.randomUUID();
    private final UUID unbookableId = UUID.randomUUID();

    @Test
    @DisplayName("anonymous caller — only the rows whose assignment passes the strict verdict")
    void should_keepOnlyBookableRows_when_callerIsAnonymous() {
        List<MasterServiceResponse> services = List.of(row(bookableId), row(unbookableId));
        when(bookingMasterService.getBookableAssignmentIds(masterId)).thenReturn(Set.of(bookableId));

        List<MasterServiceResponse> result = filter.forViewer(masterId, services, null);

        assertThat(result).extracting(MasterServiceResponse::id).containsExactly(bookableId);
        verifyNoInteractions(authorizationService);
    }

    @Test
    @DisplayName("anonymous Spring token — treated like no authentication (filtered, no authz lookup)")
    void should_filter_when_anonymousTokenPresent() {
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        when(bookingMasterService.getBookableAssignmentIds(masterId)).thenReturn(Set.of());

        List<MasterServiceResponse> result = filter.forViewer(masterId, List.of(row(unbookableId)), anonymous);

        assertThat(result).isEmpty();
        verifyNoInteractions(authorizationService);
    }

    @Test
    @DisplayName("CLIENT — filtered (canReadMasterSchedule says no)")
    void should_filter_when_callerIsClient() {
        Authentication client = authenticated("ROLE_CLIENT");
        when(authorizationService.canReadMasterSchedule(client, masterId)).thenReturn(false);
        when(bookingMasterService.getBookableAssignmentIds(masterId)).thenReturn(Set.of(bookableId));

        List<MasterServiceResponse> result =
                filter.forViewer(masterId, List.of(row(bookableId), row(unbookableId)), client);

        assertThat(result).extracting(MasterServiceResponse::id).containsExactly(bookableId);
    }

    @Test
    @DisplayName("the master themself / their salon owner — the full configured list, verdict never computed")
    void should_returnUnfiltered_when_callerManagesTheMaster() {
        Authentication owner = authenticated("ROLE_SALON_OWNER");
        when(authorizationService.canReadMasterSchedule(owner, masterId)).thenReturn(true);
        List<MasterServiceResponse> services = List.of(row(bookableId), row(unbookableId));

        List<MasterServiceResponse> result = filter.forViewer(masterId, services, owner);

        assertThat(result).isSameAs(services);
        verifyNoInteractions(bookingMasterService);
    }

    @Test
    @DisplayName("empty list — returned as-is with no verdict and no authz lookup")
    void should_shortCircuit_when_listEmpty() {
        List<MasterServiceResponse> result = filter.forViewer(masterId, List.of(), authenticated("ROLE_CLIENT"));

        assertThat(result).isEmpty();
        verifyNoInteractions(bookingMasterService, authorizationService);
    }

    private static Authentication authenticated(String role) {
        var token = new UsernamePasswordAuthenticationToken(
                "user@beautica.test", null, List.of(new SimpleGrantedAuthority(role)));
        token.setDetails(UUID.randomUUID());
        return token;
    }

    private static MasterServiceResponse row(UUID id) {
        MasterServiceResponse response = mock(MasterServiceResponse.class);
        org.mockito.Mockito.lenient().when(response.id()).thenReturn(id);
        return response;
    }
}

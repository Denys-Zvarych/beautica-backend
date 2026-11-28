package com.beautica.review.service;

import com.beautica.booking.entity.Booking;
import com.beautica.review.config.ClientRatingProperties;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("ClientRatingEligibility#rateable — SQL twin of isRateable")
class ClientRatingEligibilityTest {

    @SuppressWarnings("unchecked")
    private final Root<Booking> root = mock(Root.class);
    private final CriteriaQuery<?> query = mock(CriteriaQuery.class);
    private final CriteriaBuilder cb = mock(CriteriaBuilder.class);
    @SuppressWarnings("unchecked")
    private final Path<Object> clientPath = mock(Path.class);
    @SuppressWarnings("unchecked")
    private final Path<Object> phonePath = mock(Path.class);

    @Test
    @DisplayName("flag off: client IS NOT NULL only, no guest arm")
    void should_requireRegisteredClientOnly_when_guestFlagOff() {
        stubPaths();

        new ClientRatingEligibility(new ClientRatingProperties(false)).rateable().toPredicate(root, query, cb);

        verify(cb).isNotNull(clientPath);
        verify(cb, never()).isNotNull(phonePath);
        verify(cb, never()).or(any(Predicate.class), any(Predicate.class));
    }

    @Test
    @DisplayName("flag on: client IS NOT NULL OR guest_phone IS NOT NULL (anonymised term redundant under V260 CHECK)")
    void should_addGuestPhoneArm_when_guestFlagOn() {
        stubPaths();

        new ClientRatingEligibility(new ClientRatingProperties(true)).rateable().toPredicate(root, query, cb);

        verify(cb).isNotNull(clientPath);
        verify(cb).isNotNull(phonePath);
        verify(cb, never()).isNull(any());
        verify(root, never()).get("guestPiiAnonymisedAt");
        verify(cb).or(any(Predicate.class), any(Predicate.class));
    }

    private void stubPaths() {
        doReturn(clientPath).when(root).get("client");
        doReturn(phonePath).when(root).get("guestPhone");
        doReturn(mock(Predicate.class)).when(cb).isNotNull(any());
        doReturn(mock(Predicate.class)).when(cb).isNull(any());
        doReturn(mock(Predicate.class)).when(cb).and(any(Predicate.class), any(Predicate.class));
        doReturn(mock(Predicate.class)).when(cb).or(any(Predicate.class), any(Predicate.class));
    }
}

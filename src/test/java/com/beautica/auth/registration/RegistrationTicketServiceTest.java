package com.beautica.auth.registration;

import com.beautica.auth.TokenGenerator;
import com.beautica.common.exception.IdentityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegistrationTicketService — single-use 15-minute registration tickets (B514, AUTH-41)")
class RegistrationTicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final String PHONE = "+380501234567";
    private static final String RAW = "raw-ticket";
    private static final String HASH = "a".repeat(64);

    @Mock
    private RegistrationTicketRepository repository;
    @Mock
    private TokenGenerator tokenGenerator;

    private RegistrationTicketService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationTicketService(repository, tokenGenerator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("should_persistHashOnlyWith15MinTtl_when_issued")
    void should_persistHashOnlyWith15MinTtl_when_issued() {
        when(tokenGenerator.generateToken()).thenReturn(RAW);
        when(tokenGenerator.hash(RAW)).thenReturn(HASH);

        String raw = service.issue(PHONE);

        ArgumentCaptor<RegistrationTicket> saved = ArgumentCaptor.forClass(RegistrationTicket.class);
        verify(repository).save(saved.capture());
        assertThat(raw).isEqualTo(RAW);
        assertThat(saved.getValue().getPhone()).isEqualTo(PHONE);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(RegistrationTicketService.TICKET_TTL));
        assertThat(saved.getValue().isUsed()).isFalse();
    }

    @Test
    @DisplayName("should_returnPhoneAndMarkUsed_when_consumedWhileLive")
    void should_returnPhoneAndMarkUsed_when_consumedWhileLive() {
        RegistrationTicket ticket = RegistrationTicket.issue(HASH, PHONE, NOW.plusSeconds(60));
        when(tokenGenerator.hash(RAW)).thenReturn(HASH);
        when(repository.findByTicketHashForUpdate(HASH)).thenReturn(Optional.of(ticket));

        String phone = service.consume(RAW);

        assertThat(phone).isEqualTo(PHONE);
        assertThat(ticket.isUsed()).isTrue();
    }

    @Test
    @DisplayName("should_throwTicketInvalid_when_alreadyUsed")
    void should_throwTicketInvalid_when_alreadyUsed() {
        RegistrationTicket ticket = RegistrationTicket.issue(HASH, PHONE, NOW.plusSeconds(60));
        ticket.markUsed();
        when(tokenGenerator.hash(RAW)).thenReturn(HASH);
        when(repository.findByTicketHashForUpdate(HASH)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.consume(RAW)).isInstanceOfSatisfying(IdentityException.class,
                e -> assertThat(e.getCode()).isEqualTo(IdentityException.Code.REGISTRATION_TICKET_INVALID));
    }

    @Test
    @DisplayName("should_throwTicketInvalid_when_expired")
    void should_throwTicketInvalid_when_expired() {
        RegistrationTicket ticket = RegistrationTicket.issue(HASH, PHONE, NOW);
        when(tokenGenerator.hash(RAW)).thenReturn(HASH);
        when(repository.findByTicketHashForUpdate(HASH)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.consume(RAW)).isInstanceOf(IdentityException.class);
        assertThat(ticket.isUsed()).isFalse();
    }

    @Test
    @DisplayName("should_throwTicketInvalid_when_unknownOrBlank")
    void should_throwTicketInvalid_when_unknownOrBlank() {
        when(tokenGenerator.hash(RAW)).thenReturn(HASH);
        when(repository.findByTicketHashForUpdate(HASH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consume(RAW)).isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> service.consume(" ")).isInstanceOf(IdentityException.class);
        verify(tokenGenerator, never()).hash(" ");
    }
}

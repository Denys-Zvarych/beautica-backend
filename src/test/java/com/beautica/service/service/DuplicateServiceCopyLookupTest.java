package com.beautica.service.service;

import com.beautica.service.entity.OwnerType;
import com.beautica.service.repository.ActiveDuplicateProjection;
import com.beautica.service.repository.ServiceRepository;
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit pins for {@link DuplicateServiceCopyLookup}'s pool guard: the race-loser lookup must never
 * wait on the pool while its caller still holds the aborted connection, so on a fully saturated
 * Hikari pool (no idle connection, total at maximum) it returns no rows WITHOUT opening a transaction
 * (the caller then answers with the plain {@code DUPLICATE_SERVICE} 409); with one idle, or with
 * room for Hikari to open a new one, it runs in a read-only, 2 s {@code REQUIRES_NEW} transaction.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DuplicateServiceCopyLookup — idle-connection guard + REQUIRES_NEW lookup")
class DuplicateServiceCopyLookupTest {

    private static final UUID[] TYPE_IDS = {UUID.randomUUID()};
    private static final Integer[] DURATIONS = {60};
    private static final String[] NAMES = {"Класичний масаж"};

    @Mock private ServiceRepository serviceRepository;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private DataSource dataSource;
    @Mock private HikariDataSource hikariDataSource;
    @Mock private HikariPoolMXBean pool;
    @Mock private HikariConfigMXBean config;

    private UUID ownerId;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
    }

    @Test
    @DisplayName("fully saturated pool (none idle, total = max) → empty result, no transaction begun, repository never queried")
    void should_returnEmptyWithoutTouchingThePool_when_poolIsSaturated() throws Exception {
        DuplicateServiceCopyLookup lookup = hikariBackedLookup(0);
        stubPoolSize(10, 10);

        List<ActiveDuplicateProjection> rows = lookup.findCommittedCopies(
                OwnerType.SALON, ownerId, TYPE_IDS, DURATIONS, NAMES);

        assertThat(rows).as("plain-409 fallback signal").isEmpty();
        verify(transactionManager, never()).getTransaction(any());
        verify(serviceRepository, never()).findActiveDuplicateCopies(anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an idle pooled connection → the lookup runs in a read-only REQUIRES_NEW transaction with the 2 s timeout")
    void should_queryInReadOnlyRequiresNewTransaction_when_aConnectionIsIdle() throws Exception {
        DuplicateServiceCopyLookup lookup = hikariBackedLookup(1);
        ActiveDuplicateProjection winner = new ActiveDuplicateProjection(TYPE_IDS[0], UUID.randomUUID(), 60, NAMES[0]);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus(true));
        when(serviceRepository.findActiveDuplicateCopies("SALON", ownerId, TYPE_IDS, DURATIONS, NAMES))
                .thenReturn(List.of(winner));

        List<ActiveDuplicateProjection> rows = lookup.findCommittedCopies(
                OwnerType.SALON, ownerId, TYPE_IDS, DURATIONS, NAMES);

        assertThat(rows).containsExactly(winner);
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getTimeout()).isEqualTo(DuplicateServiceCopyLookup.LOOKUP_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("none idle but total below max (Hikari opens a new connection) → the lookup runs")
    void should_runLookup_when_noConnectionIsIdleButPoolCanGrow() throws Exception {
        DuplicateServiceCopyLookup lookup = hikariBackedLookup(0);
        stubPoolSize(9, 10);
        ActiveDuplicateProjection winner = new ActiveDuplicateProjection(TYPE_IDS[0], UUID.randomUUID(), 60, NAMES[0]);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus(true));
        when(serviceRepository.findActiveDuplicateCopies("SALON", ownerId, TYPE_IDS, DURATIONS, NAMES))
                .thenReturn(List.of(winner));

        List<ActiveDuplicateProjection> rows = lookup.findCommittedCopies(
                OwnerType.SALON, ownerId, TYPE_IDS, DURATIONS, NAMES);

        assertThat(rows).containsExactly(winner);
        verify(transactionManager).getTransaction(any());
    }

    @Test
    @DisplayName("a non-Hikari DataSource skips the idle check → the lookup runs")
    void should_runLookup_when_dataSourceIsNotHikari() throws Exception {
        when(dataSource.isWrapperFor(HikariDataSource.class)).thenReturn(false);
        DuplicateServiceCopyLookup lookup = new DuplicateServiceCopyLookup(serviceRepository, transactionManager, dataSource);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus(true));
        when(serviceRepository.findActiveDuplicateCopies("INDEPENDENT_MASTER", ownerId, TYPE_IDS, DURATIONS, NAMES))
                .thenReturn(List.of());

        List<ActiveDuplicateProjection> rows = lookup.findCommittedCopies(
                OwnerType.INDEPENDENT_MASTER, ownerId, TYPE_IDS, DURATIONS, NAMES);

        assertThat(rows).isEmpty();
        verify(serviceRepository).findActiveDuplicateCopies("INDEPENDENT_MASTER", ownerId, TYPE_IDS, DURATIONS, NAMES);
    }

    @Test
    @DisplayName("a DataSource whose unwrap check throws is treated as non-Hikari → the lookup runs, never an exception")
    void should_runLookup_when_unwrapCheckThrows() throws Exception {
        when(dataSource.isWrapperFor(HikariDataSource.class)).thenThrow(new SQLException("closed"));
        DuplicateServiceCopyLookup lookup = new DuplicateServiceCopyLookup(serviceRepository, transactionManager, dataSource);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus(true));
        when(serviceRepository.findActiveDuplicateCopies("SALON", ownerId, TYPE_IDS, DURATIONS, NAMES))
                .thenReturn(List.of());

        lookup.findCommittedCopies(OwnerType.SALON, ownerId, TYPE_IDS, DURATIONS, NAMES);

        verify(serviceRepository).findActiveDuplicateCopies("SALON", ownerId, TYPE_IDS, DURATIONS, NAMES);
        verifyNoInteractions(hikariDataSource);
    }

    private DuplicateServiceCopyLookup hikariBackedLookup(int idleConnections) throws SQLException {
        when(dataSource.isWrapperFor(HikariDataSource.class)).thenReturn(true);
        when(dataSource.unwrap(HikariDataSource.class)).thenReturn(hikariDataSource);
        when(hikariDataSource.getHikariPoolMXBean()).thenReturn(pool);
        when(pool.getIdleConnections()).thenReturn(idleConnections);
        return new DuplicateServiceCopyLookup(serviceRepository, transactionManager, dataSource);
    }

    private void stubPoolSize(int totalConnections, int maximumPoolSize) {
        when(pool.getTotalConnections()).thenReturn(totalConnections);
        when(hikariDataSource.getHikariConfigMXBean()).thenReturn(config);
        when(config.getMaximumPoolSize()).thenReturn(maximumPoolSize);
    }
}

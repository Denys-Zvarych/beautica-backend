package com.beautica.auth;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.RegistrationResponse;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.auth.registration.RegistrationTicketService;
import com.beautica.common.exception.IdentityException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.OtpSmsSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phone-first hardening B12 (P2) — registration hashes the password before its transaction and then consumes the
 * ticket + inserts the account in one short transaction. Two racing registrations, with the same ticket or with two
 * tickets for the same phone, still yield exactly one account; the loser gets a clean 400 / 409, never a 500.
 *
 * <p>Same context as {@code PhoneOtpVerifyConcurrencyTest} (pool 40).
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=40")
@Import(TestSecurityConfig.class)
@DisplayName("Phone registration — one account per ticket and per phone under a race (B12)")
class PhoneRegistrationRaceIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Str0ng!Passw0rd#";

    @Autowired
    private AuthService authService;

    @Autowired
    private RegistrationTicketService ticketService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private OtpSmsSender otpSmsSender;

    private String issueTicket(String phone) {
        return new TransactionTemplate(transactionManager).execute(status -> ticketService.issue(phone));
    }

    private static RegisterRequest clientWith(String ticket) {
        return new RegisterRequest(null, PASSWORD, SelfRegistrationRole.CLIENT, "Олена", null, null, null,
                null, null, null, ticket);
    }

    private List<Object> registerTogether(String... tickets) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tickets.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (String ticket : tickets) {
                Callable<Object> task = () -> {
                    start.await();
                    try {
                        return authService.register(clientWith(ticket));
                    } catch (RuntimeException e) {
                        return e;
                    }
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private int accountsWith(String phone) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone_number = ?", Integer.class, phone);
    }

    @Test
    @DisplayName("should_createOneAccount_when_sameTicketRegisteredTwiceInParallel")
    void should_createOneAccount_when_sameTicketRegisteredTwiceInParallel() throws Exception {
        String phone = "+380671120001";
        String ticket = issueTicket(phone);

        List<Object> outcomes = registerTogether(ticket, ticket);

        assertThat(outcomes).filteredOn(RegistrationResponse.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(IdentityException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((IdentityException) e).getCode())
                        .isEqualTo(IdentityException.Code.REGISTRATION_TICKET_INVALID));
        assertThat(accountsWith(phone)).isOne();
    }

    @Test
    @DisplayName("should_createOneAccount_when_twoTicketsForOnePhoneRegisteredInParallel")
    void should_createOneAccount_when_twoTicketsForOnePhoneRegisteredInParallel() throws Exception {
        String phone = "+380671120002";
        String first = issueTicket(phone);
        String second = issueTicket(phone);

        List<Object> outcomes = registerTogether(first, second);

        assertThat(outcomes).filteredOn(RegistrationResponse.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(IdentityException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((IdentityException) e).getCode())
                        .isEqualTo(IdentityException.Code.PHONE_ALREADY_REGISTERED));
        assertThat(accountsWith(phone)).isOne();
    }

    @Test
    @DisplayName("should_storeBcryptOfThePassword_when_registeredByTicket")
    void should_storeBcryptOfThePassword_when_registeredByTicket() {
        String phone = "+380671120003";

        authService.register(clientWith(issueTicket(phone)));

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE phone_number = ?", String.class,
                phone);
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches(PASSWORD, hash))
                .isTrue();
    }
}

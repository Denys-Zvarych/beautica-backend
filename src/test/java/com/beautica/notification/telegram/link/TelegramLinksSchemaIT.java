package com.beautica.notification.telegram.link;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.GUEST_PHONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 405 — V224's CHECKs reject what the service must never write. Raw SQL on purpose: these are the DB's
 * last line, independent of the entity code. Same context configuration as {@link TelegramLinkServiceIT}.
 */
@Import({TestSecurityConfig.class, TelegramLinkTestSupport.ClockConfig.class})
@TestPropertySource(properties = TelegramLinkTestSupport.BOT_USERNAME_PROPERTY)
@DisplayName("V224 telegram_links / telegram_link_tokens — constraints (phase 405)")
class TelegramLinksSchemaIT extends AbstractIntegrationTest {

    private static final String HASH32 = "decode(repeat('ab', 32), 'hex')";
    private static final String INSERT_TOKEN = "INSERT INTO telegram_link_tokens "
            + "(token_hash, booking_id, phone_hash, state, expires_at, claimed_chat_id, claimed_at, finished_at) "
            + "VALUES (decode(md5(random()::text) || md5(random()::text), 'hex'), ?, " + HASH32
            + ", ?, now() + interval '30 minutes', ?, ?::timestamptz, ?::timestamptz)";
    private static final String INSERT_LINK = "INSERT INTO telegram_links "
            + "(id, phone_hash, chat_id, status, linked_at, status_changed_at) VALUES (?, %s, 42, ?, now(), now())";

    @Autowired private PasswordEncoder passwordEncoder;

    private UUID bookingId;

    @BeforeEach
    void setUp() {
        bookingId = new TelegramLinkTestSupport(jdbcTemplate, passwordEncoder).guestBooking(GUEST_PHONE);
    }

    private void insertToken(String state, Long chatId, String claimedAt, String finishedAt) {
        jdbcTemplate.update(INSERT_TOKEN, bookingId, state, chatId, claimedAt, finishedAt);
    }

    @Test
    @DisplayName("every legal token shape inserts")
    void should_acceptLegalTokenRows() {
        assertThatCode(() -> {
            insertToken("ISSUED", null, null, null);
            insertToken("CLAIMED", 42L, "2026-10-30T10:00:00Z", null);
            insertToken("LINKED", 42L, "2026-10-30T10:00:00Z", "2026-10-30T10:01:00Z");
            insertToken("MISMATCH", 42L, "2026-10-30T10:00:00Z", "2026-10-30T10:01:00Z");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("unknown token state → violation")
    void should_reject_when_unknownTokenState() {
        assertThatThrownBy(() -> insertToken("EXPIRED", 42L, "2026-10-30T10:00:00Z", null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_tg_token_state");
    }

    @Test
    @DisplayName("ISSUED with a chat id → violation")
    void should_reject_when_issuedHasChat() {
        assertThatThrownBy(() -> insertToken("ISSUED", 42L, "2026-10-30T10:00:00Z", null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_tg_token_claim");
    }

    @Test
    @DisplayName("CLAIMED without a chat id → violation")
    void should_reject_when_claimedWithoutChat() {
        assertThatThrownBy(() -> insertToken("CLAIMED", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_tg_token_claim");
    }

    @Test
    @DisplayName("chat id without claimed_at → violation")
    void should_reject_when_claimNotPaired() {
        assertThatThrownBy(() -> insertToken("CLAIMED", 42L, null, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_tg_token_claim_pair");
    }

    @Test
    @DisplayName("terminal state without finished_at → violation")
    void should_reject_when_terminalWithoutFinishedAt() {
        assertThatThrownBy(() -> insertToken("LINKED", 42L, "2026-10-30T10:00:00Z", null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_tg_token_finished");
    }

    @Test
    @DisplayName("unknown link status → violation")
    void should_reject_when_unknownLinkStatus() {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_LINK.formatted(HASH32), UUID.randomUUID(), "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_tg_link_status");
    }

    @Test
    @DisplayName("phone hash not 32 bytes → violation")
    void should_reject_when_phoneHashWrongLength() {
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_LINK.formatted("decode('ab', 'hex')"),
                UUID.randomUUID(), "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_tg_link_phone_hash_len");
    }

    @Test
    @DisplayName("a second link row for one phone hash → unique violation")
    void should_reject_when_duplicatePhoneHash() {
        jdbcTemplate.update(INSERT_LINK.formatted(HASH32), UUID.randomUUID(), "ACTIVE");

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_LINK.formatted(HASH32), UUID.randomUUID(), "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("fresh DB: Flyway applied V224 successfully and created every V224 constraint and index")
    void should_haveAppliedV224_when_freshDatabaseMigrated() {
        assertThat(jdbcTemplate.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '224'",
                Boolean.class)).isTrue();
        List<String> constraints = jdbcTemplate.queryForList("SELECT conname FROM pg_constraint "
                + "WHERE conrelid IN ('telegram_links'::regclass, 'telegram_link_tokens'::regclass) "
                + "AND contype IN ('c', 'u', 'f')", String.class);
        assertThat(constraints).contains("ux_telegram_links_phone", "chk_tg_link_status", "chk_tg_link_phone_hash_len",
                "chk_tg_token_state", "chk_tg_token_claim", "chk_tg_token_claim_pair", "chk_tg_token_finished",
                "chk_tg_token_hash_len", "chk_tg_token_phone_hash_len");
        assertThat(jdbcTemplate.queryForObject("SELECT confdeltype::text FROM pg_constraint WHERE contype = 'f' "
                + "AND conrelid = 'telegram_link_tokens'::regclass AND confrelid = 'bookings'::regclass",
                String.class)).as("booking FK is ON DELETE CASCADE").isEqualTo("c");
        List<String> indexes = jdbcTemplate.queryForList("SELECT indexname FROM pg_indexes "
                + "WHERE tablename IN ('telegram_links', 'telegram_link_tokens')", String.class);
        assertThat(indexes).contains("idx_telegram_links_chat", "idx_telegram_links_blocked", "idx_tg_tokens_booking",
                "idx_tg_tokens_phone", "idx_tg_tokens_claim", "idx_tg_tokens_expiry");
    }

    static Stream<Arguments> illegalTokenRows() {
        String at = "2026-10-30T10:00:00Z";
        String done = "2026-10-30T10:01:00Z";
        return Stream.of(
                Arguments.of("claimed_at without a chat id", "ISSUED", null, at, null, "chk_tg_token_claim_pair"),
                Arguments.of("ISSUED with finished_at", "ISSUED", null, null, done, "chk_tg_token_finished"),
                Arguments.of("CLAIMED with finished_at", "CLAIMED", 42L, at, done, "chk_tg_token_finished"),
                Arguments.of("MISMATCH without finished_at", "MISMATCH", 42L, at, null, "chk_tg_token_finished"),
                Arguments.of("LINKED without a chat id", "LINKED", null, null, done, "chk_tg_token_claim"),
                Arguments.of("MISMATCH without a chat id", "MISMATCH", null, null, done, "chk_tg_token_claim"));
    }

    @ParameterizedTest(name = "{0} → {5}")
    @MethodSource("illegalTokenRows")
    @DisplayName("illegal token claim / finished_at pairings → named CHECK violation")
    void should_reject_when_tokenColumnsInconsistent(String scenario, String state, Long chatId, String claimedAt,
                                                     String finishedAt, String constraint) {
        assertThatThrownBy(() -> insertToken(state, chatId, claimedAt, finishedAt))
                .as(scenario).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }

    private void insertRawToken(byte[] tokenHash, byte[] phoneHash, UUID booking) {
        jdbcTemplate.update("INSERT INTO telegram_link_tokens (token_hash, booking_id, phone_hash, state, expires_at) "
                + "VALUES (?, ?, ?, 'ISSUED', now() + interval '30 minutes')", tokenHash, booking, phoneHash);
    }

    @Test
    @DisplayName("32-byte token and phone hashes insert (boundary accepted)")
    void should_accept_when_hashesExactly32Bytes() {
        assertThatCode(() -> insertRawToken(new byte[32], new byte[32], bookingId)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "token hash of {0} bytes → chk_tg_token_hash_len")
    @ValueSource(ints = {31, 33, 64})
    @DisplayName("token hash not 32 bytes → violation")
    void should_reject_when_tokenHashWrongLength(int length) {
        assertThatThrownBy(() -> insertRawToken(new byte[length], new byte[32], bookingId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_tg_token_hash_len");
    }

    @ParameterizedTest(name = "token phone hash of {0} bytes → chk_tg_token_phone_hash_len")
    @ValueSource(ints = {0, 31, 33})
    @DisplayName("token phone hash not 32 bytes → violation")
    void should_reject_when_tokenPhoneHashWrongLength(int length) {
        assertThatThrownBy(() -> insertRawToken(new byte[32], new byte[length], bookingId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_tg_token_phone_hash_len");
    }

    @Test
    @DisplayName("token for an unknown booking → FK violation")
    void should_reject_when_bookingUnknown() {
        assertThatThrownBy(() -> insertRawToken(new byte[32], new byte[32], UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens", Integer.class)).isZero();
    }

    @Test
    @DisplayName("NULL token state / link status → NOT NULL violation (a NULL cannot slip past the CHECK)")
    void should_reject_when_stateOrStatusNull() {
        assertThatThrownBy(() -> insertToken(null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_LINK.formatted(HASH32), UUID.randomUUID(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("D5: chat_id is not unique — one chat may hold links for two phone hashes")
    void should_acceptTwoPhones_when_sameChat() {
        jdbcTemplate.update(INSERT_LINK.formatted(HASH32), UUID.randomUUID(), "ACTIVE");

        assertThatCode(() -> jdbcTemplate.update(INSERT_LINK.formatted("decode(repeat('cd', 32), 'hex')"),
                UUID.randomUUID(), "BLOCKED")).doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_links WHERE chat_id = 42",
                Integer.class)).isEqualTo(2);
    }
}

package com.beautica.notification.inapp.push;

import com.beautica.config.FirebaseConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Shared setup for ITs that run with {@code FIREBASE_ENABLED=true}: {@code @Import} it to replace the Firebase sender
 * with a Mockito mock (the context then builds the push beans but never reaches Firebase; {@code @Autowired} the
 * {@link FirebaseConfig.FirebaseSender} to verify sends), and call {@link #registerAndroidDevice} to give a user the
 * active device token that makes a feed write enqueue an {@code INAPP_PUSH} outbox row.
 */
@TestConfiguration(proxyBeanMethods = false)
@MockBean(FirebaseConfig.FirebaseSender.class)
public class PushTestSupport {

    /** Inserts an active ANDROID device token {@code token} for {@code userId}. */
    public static void registerAndroidDevice(JdbcTemplate jdbcTemplate, UUID userId, String token) {
        jdbcTemplate.update("INSERT INTO device_tokens (user_id, token, platform) VALUES (?, ?, 'ANDROID')",
                userId, token);
    }
}

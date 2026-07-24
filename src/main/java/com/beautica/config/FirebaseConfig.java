package com.beautica.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Base64;

@Slf4j
@Configuration
public class FirebaseConfig {

    public interface FirebaseSender {
        String send(Message message) throws FirebaseMessagingException;
    }

    @Value("${app.firebase.service-account:}")
    private String serviceAccountJson;

    @Value("${FIREBASE_ENABLED:false}")
    private boolean firebaseEnabled;

    @Autowired
    private Environment environment;

    @Bean
    public FirebaseSender firebaseSender() throws Exception {
        if (!firebaseEnabled || serviceAccountJson == null || serviceAccountJson.isBlank()) {
            boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
            if (firebaseEnabled && isProd) {
                throw new IllegalStateException(
                        "Firebase is enabled but FIREBASE_SERVICE_ACCOUNT is not configured");
            }
            log.warn("Firebase is disabled or credentials are absent — push notifications will be suppressed");
            return message -> {
                log.warn("No-op FirebaseSender invoked — push notification not sent");
                return "no-op";
            };
        }

        byte[] jsonBytes = Base64.getMimeDecoder().decode(serviceAccountJson.strip());
        GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(jsonBytes));

        if (FirebaseApp.getApps().isEmpty()) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setConnectTimeout(5000)
                    .setReadTimeout(10000)
                    .build();
            FirebaseApp.initializeApp(options);
        }

        return FirebaseMessaging.getInstance()::send;
    }
}

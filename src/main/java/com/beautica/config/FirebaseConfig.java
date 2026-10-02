package com.beautica.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
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
public class FirebaseConfig implements DisposableBean {

    public interface FirebaseSender {
        String send(Message message) throws FirebaseMessagingException;
    }

    @Value("${app.firebase.service-account:}")
    private String serviceAccountJson;

    @Value("${FIREBASE_ENABLED:false}")
    private boolean firebaseEnabled;

    @Autowired
    private Environment environment;

    /**
     * The {@link FirebaseApp} THIS config initialised, or {@code null} when push is off or the config
     * reused an app it did not create. Only an owned app is deleted in {@link #destroy()}.
     */
    private volatile FirebaseApp ownedApp;

    @Bean
    public FirebaseSender firebaseSender() throws Exception {
        if (!firebaseEnabled) {
            log.warn("Firebase push disabled — push notifications will be suppressed");
            return noOpSender();
        }

        // Prod runs on the DEFAULT profile (Railway activates no "prod" profile), so only an explicit
        // "local" profile may degrade to the no-op sender; anything else fails fast.
        boolean local = Arrays.asList(environment.getActiveProfiles()).contains("local");

        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            if (!local) {
                throw new IllegalStateException(
                        "Firebase is enabled but FIREBASE_SERVICE_ACCOUNT is not configured");
            }
            log.warn("Firebase enabled but FIREBASE_SERVICE_ACCOUNT is blank — push notifications will be suppressed");
            return noOpSender();
        }

        GoogleCredentials credentials;
        try {
            byte[] jsonBytes = Base64.getMimeDecoder().decode(serviceAccountJson.strip());
            credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(jsonBytes));
        } catch (Exception ex) {
            if (!local) {
                throw ex;
            }
            // Local dev: a malformed key must not block boot. Log the class only — the message can
            // echo fragments of the credential.
            log.warn("Firebase service account could not be parsed ({}) — push notifications will be suppressed",
                    ex.getClass().getSimpleName());
            return noOpSender();
        }

        String projectId = credentials instanceof ServiceAccountCredentials sac ? sac.getProjectId() : "unknown";
        log.info("Firebase push enabled for project {}", projectId);

        try {
            FirebaseApp app;
            if (FirebaseApp.getApps().isEmpty()) {
                app = initializeApp(credentials);
                ownedApp = app;
            } else {
                // Reused, not created here: never deleted by destroy().
                app = FirebaseApp.getInstance();
            }
            return FirebaseMessaging.getInstance(app)::send;
        } catch (Exception ex) {
            if (!local) {
                throw ex;
            }
            log.warn("Firebase initialisation failed ({}) — push notifications will be suppressed",
                    ex.getClass().getSimpleName());
            return noOpSender();
        }
    }

    /** Seam for tests; creates and registers the default {@link FirebaseApp}. */
    FirebaseApp initializeApp(GoogleCredentials credentials) {
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .setConnectTimeout(5000)
                .setReadTimeout(10000)
                .build();
        return FirebaseApp.initializeApp(options);
    }

    private static FirebaseSender noOpSender() {
        return message -> {
            log.warn("No-op FirebaseSender invoked — push notification not sent");
            return "no-op";
        };
    }

    /**
     * Deletes the {@link FirebaseApp} this config created when the context closes so a DevTools restart
     * (or any context re-creation in the same JVM) re-initialises with the current credentials. An app
     * this config merely reused (pre-existing static default app) is left untouched — it is not ours.
     * Queued push tasks are drained first: {@code pushTaskPool} is drained in its own {@code destroy()}
     * before this one, because it {@code @DependsOn("firebaseConfig")} and Spring destroys dependents
     * before their dependencies.
     */
    @Override
    public void destroy() {
        FirebaseApp app = ownedApp;
        ownedApp = null;
        if (app != null) {
            app.delete();
        }
    }
}

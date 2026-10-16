package com.beautica.ops;

import com.beautica.TestPostgresImage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerStatus;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Phase 366 round trip of the nightly backup image ({@code ops/db-backup}) against REAL dependencies on
 * one Docker network: the Flyway-migrated source Postgres, an S3-compatible gateway standing in for R2
 * (it verifies SigV4 and the {@code x-amz-content-sha256} header, as R2 does), and a WireMock container
 * standing in for the Sentry Cron check-in endpoint. The image is built from the real Dockerfile.
 *
 * <p>Tagged {@code db-backup-image} (a literal, which the CI selector reads): it builds an image and pulls three more, so it is excluded from the default
 * Gradle test task and from the sharded CI selection, and runs in its own CI job (see pr-validate.yml).
 * Run it with {@code ./gradlew test -PdbBackupIt --tests com.beautica.ops.DbBackupRoundTripIT}.
 *
 * <p>Every credential is generated per run, so the leak assertions use the actual secret strings.
 */
@Tag("db-backup-image")
@DisplayName("DbBackupRoundTripIT: ops/db-backup image against real Postgres, S3 gateway and Sentry stub")
class DbBackupRoundTripIT {

    // The S3 gateway verifies SigV4 + payload hash like R2. MinIO no longer publishes public images.
    private static final String S3_GATEWAY_IMAGE = "ghcr.io/versity/versitygw:v1.9.0";
    private static final String WIREMOCK_IMAGE = "wiremock/wiremock:3.9.1";
    // The staging bucket name is the backup script's "non-production" signal: it is the only thing that lets the
    // IT use http:// endpoints and an unpinned recipient. backup.sh refuses it when RAILWAY_ENVIRONMENT_NAME=production.
    private static final String BACKUP_BUCKET = "beautica-db-backups-staging";
    private static final String PROD_BUCKET = "beautica-db-backups-prod";
    // Syntactically a valid prod EU endpoint; mapped to 127.0.0.1 in the container so no test traffic leaves the host.
    private static final String FAKE_EU_HOST = "0123456789abcdef0123456789abcdef.eu.r2.cloudflarestorage.com";
    // A syntactically valid age recipient that is NOT the run's key.
    private static final String OTHER_RECIPIENT = "age1" + "q".repeat(58);
    private static final String PIN_FILE = "/etc/db-backup/prod-recipient.txt";
    private static final String BACKSLASH_USER = "bs_user";
    private static final String BACKSLASH_PASSWORD = "p\\x41q";   // a literal backslash in the password
    private static final String MEDIA_BUCKET = "beautica-media-prod";
    private static final String SENTRY_PATH = "/cron/beautica-db-backup/";
    private static final String AGE_MAGIC = "age-encryption.org/v1";
    private static final Pattern RESULT_LINE = Pattern.compile("(?m)^db_backup_result .*$");
    private static final Pattern STATUS_PARAM = Pattern.compile("status=(\\w+)");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    // Per-run credentials: nothing here is a real secret and nothing is committed.
    private static final String S3_ACCESS_KEY = "AK" + randomToken(18).toUpperCase();
    private static final String S3_SECRET_KEY = randomToken(32);
    private static final String DB_PASSWORD = randomToken(24);
    private static final String SENTRY_KEY = randomToken(24);

    private static final Network NETWORK = Network.newNetwork();
    private static final ImageFromDockerfile BACKUP_IMAGE = new ImageFromDockerfile()
            .withFileFromPath(".", Path.of("ops/db-backup").toAbsolutePath());

    private static PostgreSQLContainer<?> source;
    private static GenericContainer<?> s3Gateway;
    private static GenericContainer<?> sentryStub;
    private static S3Client s3;
    private static String ageRecipient;
    private static String ageIdentity;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        source = new PostgreSQLContainer<>(TestPostgresImage.dockerImageName())
                .withNetwork(NETWORK).withNetworkAliases("pgsrc")
                .withDatabaseName("beautica").withUsername("backup_src").withPassword(DB_PASSWORD);
        s3Gateway = new GenericContainer<>(S3_GATEWAY_IMAGE)
                .withNetwork(NETWORK).withNetworkAliases("s3gw")
                .withEnv("ROOT_ACCESS_KEY_ID", S3_ACCESS_KEY)
                .withEnv("ROOT_SECRET_ACCESS_KEY", S3_SECRET_KEY)
                .withEnv("VGW_REGION", "auto")
                .withCommand("posix", "/tmp")
                .withExposedPorts(7070)
                .waitingFor(Wait.forListeningPort());
        sentryStub = new GenericContainer<>(WIREMOCK_IMAGE)
                .withNetwork(NETWORK).withNetworkAliases("sentrystub")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));
        Startables.deepStart(source, s3Gateway, sentryStub).join();

        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://" + s3Gateway.getHost() + ":" + s3Gateway.getMappedPort(7070)))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(S3_ACCESS_KEY, S3_SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        s3.createBucket(b -> b.bucket(BACKUP_BUCKET));
        s3.createBucket(b -> b.bucket(MEDIA_BUCKET));

        Flyway.configure()
                .dataSource(source.getJdbcUrl(), source.getUsername(), source.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        seedUsers();
        createBackslashRole();

        // A throwaway age keypair, generated with the image's own age-keygen.
        OneShot keygen = runOneShot(new String[] {"age-keygen"}, Map.of(), Map.of());
        Matcher pub = Pattern.compile("(?m)^# public key: (age1[a-z0-9]+)$").matcher(keygen.output());
        Matcher secret = Pattern.compile("(?m)^(AGE-SECRET-KEY-[A-Z0-9]+)$").matcher(keygen.output());
        assertThat(pub.find() && secret.find()).as("age-keygen output").isTrue();
        ageRecipient = pub.group(1);
        ageIdentity = secret.group(1) + "\n";
    }

    @AfterAll
    static void stopInfrastructure() {
        if (s3 != null) {
            s3.close();
        }
        for (GenericContainer<?> c : new GenericContainer<?>[] {sentryStub, s3Gateway, source}) {
            if (c != null) {
                c.stop();
            }
        }
        NETWORK.close();
    }

    @BeforeEach
    void resetState() throws Exception {
        for (String bucket : List.of(BACKUP_BUCKET, MEDIA_BUCKET)) {
            for (S3Object o : s3.listObjectsV2(b -> b.bucket(bucket)).contents()) {
                s3.deleteObject(b -> b.bucket(bucket).key(o.key()));
            }
        }
        wiremock("DELETE", "/__admin/requests", null);
        wiremock("DELETE", "/__admin/mappings", null);
        stubCheckIn(200);
    }

    @Test
    @DisplayName("(a)+(d) run uploads an encrypted dump that restores equal; check-ins are in_progress then ok")
    void should_uploadEncryptedDumpThatRestoresEqual_when_backupRuns() throws Exception {
        Run run = runBackup(Map.of());

        assertThat(run.exitCode()).isZero();
        assertThat(run.resultLine()).startsWith("db_backup_result status=ok env=staging ");
        List<String> keys = keys(BACKUP_BUCKET);
        assertThat(keys).hasSize(1).allMatch(k -> k.matches("daily/beautica-\\d{8}T\\d{4}Z\\.dump\\.age"));
        byte[] object = s3.getObjectAsBytes(b -> b.bucket(BACKUP_BUCKET).key(keys.get(0))).asByteArray();
        assertThat(new String(object, 0, AGE_MAGIC.length(), StandardCharsets.ISO_8859_1))
                .as("object is age ciphertext, not a plaintext PGDMP archive").isEqualTo(AGE_MAGIC);
        assertThat(checkInStatuses()).containsExactly("in_progress", "ok");
        assertNoLeak(run.output());

        try (PostgreSQLContainer<?> target = new PostgreSQLContainer<>(TestPostgresImage.dockerImageName())
                .withNetwork(NETWORK).withNetworkAliases("pgdst")
                .withDatabaseName("restored").withUsername("backup_dst").withPassword(DB_PASSWORD)) {
            target.start();
            OneShot restore = runOneShot(
                    new String[] {"bash", "-c", "set -o pipefail; age -d -i /tmp/identity.txt /tmp/dump.age"
                            + " | pg_restore --no-owner --no-privileges --exit-on-error -h pgdst -U backup_dst -d restored"},
                    Map.of("PGPASSWORD", DB_PASSWORD),
                    Map.of("/tmp/identity.txt", Transferable.of(ageIdentity.getBytes(StandardCharsets.UTF_8)), "/tmp/dump.age", Transferable.of(object)));

            assertThat(restore.exitCode()).as(restore.output()).isZero();
            assertThat(flywayMaxVersion(target.getJdbcUrl(), "backup_dst")).isEqualTo(flywayMaxVersion(source.getJdbcUrl(), "backup_src"));
            Map<String, Long> restoredCounts = rowCounts(target.getJdbcUrl(), "backup_dst");
            assertThat(restoredCounts).isEqualTo(rowCounts(source.getJdbcUrl(), "backup_src"));
            assertThat(restoredCounts.get("users")).isEqualTo(3L);
        }
    }

    @Test
    @DisplayName("(b) BACKUP_FORCE_WEEKLY=1 stores the identical object under weekly/ as well")
    void should_writeIdenticalWeeklyObject_when_forceWeeklySet() {
        Run run = runBackup(Map.of("BACKUP_FORCE_WEEKLY", "1"));

        assertThat(run.exitCode()).isZero();
        assertThat(run.resultLine()).contains(" weekly=1 ");
        List<String> keys = keys(BACKUP_BUCKET);
        assertThat(keys).hasSize(2);
        String daily = keys.stream().filter(k -> k.startsWith("daily/")).findFirst().orElseThrow();
        String weekly = keys.stream().filter(k -> k.startsWith("weekly/")).findFirst().orElseThrow();
        assertThat(weekly.substring("weekly/".length())).isEqualTo(daily.substring("daily/".length()));
        assertThat(s3.getObjectAsBytes(b -> b.bucket(BACKUP_BUCKET).key(weekly)).asByteArray())
                .isEqualTo(s3.getObjectAsBytes(b -> b.bucket(BACKUP_BUCKET).key(daily)).asByteArray());
        assertNoLeak(run.output());
    }

    @Test
    @DisplayName("(b) without BACKUP_FORCE_WEEKLY a non-Sunday run writes no weekly/ object")
    void should_writeNoWeeklyObject_when_notSundayAndNotForced() {
        assumeFalse(LocalDate.now(Clock.systemUTC()).getDayOfWeek() == DayOfWeek.SUNDAY,
                "weekly copies are written on Sundays (UTC) by design");

        Run run = runBackup(Map.of());

        assertThat(run.exitCode()).isZero();
        assertThat(run.resultLine()).contains(" weekly=0 ");
        assertThat(keys(BACKUP_BUCKET)).hasSize(1).allMatch(k -> k.startsWith("daily/"));
    }

    @Test
    @DisplayName("(c) BACKUP_DB_SIZE_WARN_BYTES=1 still uploads, exits 2, status=size_warn, check-ins in_progress then error")
    void should_uploadAndExitTwo_when_databaseAboveSizeThreshold() throws Exception {
        Run run = runBackup(Map.of("BACKUP_DB_SIZE_WARN_BYTES", "1"));

        assertThat(run.exitCode()).isEqualTo(2);
        assertThat(run.resultLine()).startsWith("db_backup_result status=size_warn ");
        assertThat(keys(BACKUP_BUCKET)).hasSize(1);
        assertThat(checkInStatuses()).containsExactly("in_progress", "error");
        assertNoLeak(run.output());
    }

    @Test
    @DisplayName("(d) a failing check-in endpoint never fails the backup")
    void should_exitZero_when_checkInEndpointReturns500() throws Exception {
        wiremock("DELETE", "/__admin/mappings", null);
        stubCheckIn(500);

        Run run = runBackup(Map.of());

        assertThat(run.exitCode()).isZero();
        assertThat(run.resultLine()).startsWith("db_backup_result status=ok ");
        assertThat(keys(BACKUP_BUCKET)).hasSize(1);
        assertThat(checkInStatuses()).containsExactly("in_progress", "ok");
        assertNoLeak(run.output());
    }

    @Test
    @DisplayName("(e) a missing BACKUP_AGE_RECIPIENT fails before any pg_dump, PUT or check-in, naming the variable only")
    void should_failBeforeAnyWork_when_recipientMissing() throws Exception {
        Run run = runBackup(Map.of("BACKUP_AGE_RECIPIENT", ""));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.output()).contains("BACKUP_AGE_RECIPIENT");
        assertThat(run.resultLine()).startsWith("db_backup_result status=error ");
        assertThat(run.output()).doesNotContain("dumping").doesNotContain("uploading");
        assertThat(keys(BACKUP_BUCKET)).isEmpty();
        assertThat(checkInStatuses()).isEmpty();
        assertNoLeak(run.output());
    }

    @Test
    @DisplayName("(f) a non-backup bucket is refused before any PUT")
    void should_refuseBucket_when_notABackupBucket() throws Exception {
        Run run = runBackup(Map.of("BACKUP_R2_BUCKET", MEDIA_BUCKET));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.output()).contains("BACKUP_R2_BUCKET");
        assertThat(run.resultLine()).startsWith("db_backup_result status=error ");
        assertThat(keys(MEDIA_BUCKET)).isEmpty();
        assertThat(keys(BACKUP_BUCKET)).isEmpty();
        assertThat(checkInStatuses()).isEmpty();
        assertNoLeak(run.output());
    }

    @Test
    @DisplayName("weekly copy is a server-side copy: one body upload, the copy request is confirmed, objects are identical")
    void should_copyServerSide_when_weeklyWritten() {
        Run run = runBackup(Map.of("BACKUP_FORCE_WEEKLY", "1"));

        assertThat(run.exitCode()).isZero();
        assertThat(run.output()).contains("upload attempt 1 http=200").contains("copy attempt 1 http=200")
                .doesNotContain("upload attempt 2");
        assertThat(keys(BACKUP_BUCKET)).hasSize(2);
    }

    @Test
    @DisplayName("a 403 from the storage service is final: exactly one upload attempt, no retry")
    void should_attemptOnce_when_storageAnswers403() throws Exception {
        String wrongSecret = randomToken(32);

        Run run = runBackup(Map.of("BACKUP_R2_SECRET_ACCESS_KEY", wrongSecret));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.output()).contains("upload attempt 1 http=403").doesNotContain("upload attempt 2");
        assertThat(run.output().split("upload attempt", -1)).hasSize(2);
        assertThat(run.resultLine()).startsWith("db_backup_result status=error ");
        assertThat(run.output()).doesNotContain(wrongSecret);
        assertThat(keys(BACKUP_BUCKET)).isEmpty();
        assertThat(checkInStatuses()).containsExactly("in_progress", "error");
    }

    @Test
    @DisplayName("a literal backslash in the DATABASE_URL password is not interpreted as an escape")
    void should_authenticate_when_passwordHasRawBackslash() {
        Run run = runBackup(Map.of("DATABASE_URL",
                "postgresql://" + BACKSLASH_USER + ":" + BACKSLASH_PASSWORD + "@pgsrc:5432/beautica?sslmode=disable"));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.resultLine()).startsWith("db_backup_result status=ok ");
        assertThat(run.output()).doesNotContain(BACKSLASH_PASSWORD);
    }

    @Test
    @DisplayName("a malformed age recipient (age1zzz) is refused before any dump or PUT")
    void should_refuseRecipient_when_notAValidAgeRecipient() throws Exception {
        Run run = runBackup(Map.of("BACKUP_AGE_RECIPIENT", "age1zzz"));

        assertRefusedBeforeAnyWork(run, "BACKUP_AGE_RECIPIENT");
    }

    @Test
    @DisplayName("prod bucket + a non-R2 / plain-http endpoint is refused: no PUT, no check-in")
    void should_refuseEndpoint_when_prodBucketAndNonR2Host() throws Exception {
        Run run = runBackup(prodEnv(Map.of("BACKUP_R2_ENDPOINT", "http://s3gw:7070")));

        assertRefusedBeforeAnyWork(run, "BACKUP_R2_ENDPOINT");
        assertThat(run.resultLine()).contains(" env=prod ");
    }

    @Test
    @DisplayName("prod bucket + an https host that is not the EU R2 domain is refused")
    void should_refuseEndpoint_when_prodBucketAndHttpsLookalikeHost() throws Exception {
        Run run = runBackup(prodEnv(Map.of("BACKUP_R2_ENDPOINT", "https://" + FAKE_EU_HOST + ".evil.example")));

        assertRefusedBeforeAnyWork(run, "BACKUP_R2_ENDPOINT");
    }

    @Test
    @DisplayName("prod bucket + a recipient that differs from the committed pin is refused")
    void should_refuse_when_prodBucketWithUnpinnedRecipient() throws Exception {
        Run run = runBackup(prodEnv(Map.of()), pinFile(OTHER_RECIPIENT));

        assertRefusedBeforeAnyWork(run, "BACKUP_AGE_RECIPIENT");
        assertThat(run.output()).contains("pinned");
    }

    @Test
    @DisplayName("the staging bucket is refused in the production environment")
    void should_refuse_when_stagingBucketInProductionEnvironment() throws Exception {
        Run run = runBackup(Map.of("RAILWAY_ENVIRONMENT_NAME", "production"));

        assertRefusedBeforeAnyWork(run, "BACKUP_R2_BUCKET");
    }

    @Test
    @DisplayName("the prod bucket is refused outside the production environment")
    void should_refuse_when_prodBucketInNonProductionEnvironment() throws Exception {
        Run run = runBackup(prodEnv(Map.of("RAILWAY_ENVIRONMENT_NAME", "staging")));

        assertRefusedBeforeAnyWork(run, "BACKUP_R2_BUCKET");
    }

    @Test
    @DisplayName("prod bucket + sslmode=disable is refused")
    void should_refuse_when_prodBucketAndSslModeDisable() throws Exception {
        Run run = runBackup(prodEnv(Map.of("DATABASE_URL",
                "postgresql://backup_src:" + DB_PASSWORD + "@pgsrc:5432/beautica?sslmode=disable")), pinFile(ageRecipient));

        assertRefusedBeforeAnyWork(run, "sslmode");
    }

    @Test
    @DisplayName("prod: a non-https check-in URL is skipped with a warning (and the pin matches), never called")
    void should_skipCheckIns_when_prodSentryUrlNotHttps() throws Exception {
        Run run = runBackup(prodEnv(Map.of(
                "RAILWAY_ENVIRONMENT_NAME", "production",
                "DATABASE_URL", "postgresql://backup_src:" + DB_PASSWORD + "@pgsrc:1/beautica?sslmode=require")),
                pinFile(ageRecipient));

        assertThat(run.exitCode()).isNotZero();   // validation passed; the database at port 1 is not reachable
        assertThat(run.output()).contains("recipient pin: matched").contains("BACKUP_SENTRY_CRON_URL is not an https URL")
                .contains("database not reachable");
        assertThat(checkInStatuses()).isEmpty();
        assertNoLeak(run.output());
    }

    @Test
    @DisplayName("prod bucket + an empty pin file fails closed (exit 1): no dump, no PUT, no check-in")
    void should_refuse_when_prodBucketAndPinFileEmpty() throws Exception {
        Run run = runBackup(prodEnv(Map.of()), pinFile(""));

        assertRefusedBeforeAnyWork(run, "prod-recipient.txt");
        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.output()).contains("no prod recipient pinned").doesNotContain("recipient pin: matched");
    }

    @Test
    @DisplayName("prod bucket + the image's shipped pin file (comments only, runbook E3 not done) fails closed")
    void should_refuse_when_prodBucketAndShippedPinFileHasNoKey() throws Exception {
        Run run = runBackup(prodEnv(Map.of()));

        assertRefusedBeforeAnyWork(run, "prod-recipient.txt");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("a 503 then a 200 is retried: the second upload attempt succeeds and the run ends ok")
    void should_retryAndEndOk_when_storageAnswers503ThenOk() throws Exception {
        Run run = runBackup(Map.of("SHIM_FAULT", "unavailable"), curlShim());

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("upload attempt 1 http=503").contains("upload attempt 2 http=200")
                .doesNotContain("upload attempt 3");
        assertThat(run.resultLine()).startsWith("db_backup_result status=ok ");
        assertThat(keys(BACKUP_BUCKET)).hasSize(1);
        assertThat(checkInStatuses()).containsExactly("in_progress", "ok");
        assertNoLeak(run.output());
    }

    @Test
    @DisplayName("an upload applied server-side whose reply is lost (000) is confirmed by HEAD (size + MD5): one attempt, status ok")
    void should_confirmByHeadAndNotRetry_when_uploadReplyIsLost() throws Exception {
        Run run = runBackup(Map.of("SHIM_FAULT", "lost"), curlShim());

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("upload attempt 1 http=000").contains("upload confirmed by HEAD")
                .doesNotContain("upload attempt 2");
        assertThat(run.resultLine()).startsWith("db_backup_result status=ok ");
        assertThat(keys(BACKUP_BUCKET)).hasSize(1);
        assertThat(checkInStatuses()).containsExactly("in_progress", "ok");
        assertNoLeak(run.output());
    }

    @Test
    @DisplayName("a weekly copy applied server-side whose reply is lost (000) is confirmed by HEAD, not retried")
    void should_confirmByHeadAndNotRetry_when_copyReplyIsLost() throws Exception {
        Run run = runBackup(Map.of("SHIM_FAULT", "lost", "SHIM_PUT_N", "1", "BACKUP_FORCE_WEEKLY", "1"), curlShim());

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("upload attempt 1 http=200").contains("copy attempt 1 http=000")
                .contains("copy confirmed by HEAD").doesNotContain("copy attempt 2");
        assertThat(run.resultLine()).startsWith("db_backup_result status=ok ").contains(" weekly=1 ");
        assertThat(keys(BACKUP_BUCKET)).hasSize(2);
        assertNoLeak(run.output());
    }

    @Test
    @DisplayName("curl configs: only the body upload has a speed floor; the server-side copy and the HEAD have none, HEAD is capped at 30 s")
    void should_setSpeedFloorOnBodyUploadOnly_and_capHeadAt30s_when_copyReplyIsLost() {
        Run run = runBackup(Map.of("SHIM_FAULT", "lost", "SHIM_PUT_N", "1", "BACKUP_FORCE_WEEKLY", "1"), curlShim());

        assertThat(run.exitCode()).as(run.output()).isZero();
        List<String> cfgs = shimConfigs(run.output());
        assertThat(cfgs).as("one body PUT, one copy PUT, one HEAD (lost copy reply), in that order")
                .extracting(c -> c.replaceAll(" .*", ""))
                .containsExactly("kind=body", "kind=copy", "kind=head");
        assertThat(cfgs.get(0)).as("body upload keeps the 256 KiB/s floor; max-time 120 s + size term")
                .matches("kind=body speed_floor=1 max_time=1\\d\\d");
        assertThat(cfgs.get(1)).as("server-side copy moves no bytes: no speed floor").matches("kind=copy speed_floor=0 max_time=1\\d\\d");
        assertThat(cfgs.get(2)).as("HEAD: no speed floor, fixed 30 s max-time (not the 120 s body formula)")
                .isEqualTo("kind=head speed_floor=0 max_time=30");
    }

    @Test
    @DisplayName("deadline: a database that never becomes ready ends the run with the REAL (lowered) deadline in the message")
    void should_nameActualDeadline_when_deadlineExceededWhileWaitingForDatabase() throws Exception {
        byte[] slowNotReady = """
                #!/bin/sh
                sleep 4
                exit 1
                """.getBytes(StandardCharsets.UTF_8);

        Run run = runBackup(Map.of("BACKUP_DEADLINE_S", "12"), Map.of("/usr/local/sbin/pg_isready", Transferable.of(slowNotReady, 0755)));

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.output()).contains("db_backup: overall 12s deadline exceeded")
                .doesNotContain("780s").doesNotContain("database not reachable").doesNotContain("dumping");
        assertThat(run.resultLine()).startsWith("db_backup_result status=error ");
        assertThat(keys(BACKUP_BUCKET)).isEmpty();
        assertThat(checkInStatuses()).containsExactly("in_progress", "error");
    }

    @Test
    @DisplayName("PATH is pinned and BASH_ENV/ENV are unset: a PATH-planted pg_dump never runs and child tools see neither variable")
    void should_ignorePlantedPathAndStartupFiles_when_environmentIsHostile() {
        // Probe (bash, in the pinned PATH's first dir) reports what a child bash inherits, then runs the real tool.
        byte[] probe = """
                #!/bin/bash
                echo "probe tool=$(basename "$0") path=$PATH bash_env=${BASH_ENV-unset} env=${ENV-unset}" > /proc/1/fd/1 2>/dev/null || true
                PATH="${PATH#/usr/local/sbin:}"
                exec "$(basename "$0")" "$@"
                """.getBytes(StandardCharsets.UTF_8);
        byte[] planted = """
                #!/bin/sh
                echo "PLANTED_TOOL_RAN" > /proc/1/fd/1 2>/dev/null || true
                exit 1
                """.getBytes(StandardCharsets.UTF_8);
        Map<String, Transferable> files = Map.of(
                "/usr/local/sbin/psql", Transferable.of(probe, 0755),
                "/opt/planted/pg_dump", Transferable.of(planted, 0755),
                "/opt/planted/rc", Transferable.of("echo BASH_ENV_RAN > /proc/1/fd/1 2>/dev/null || true\n".getBytes(StandardCharsets.UTF_8), 0644));

        Run run = runBackup(Map.of(
                "PATH", "/opt/planted:/usr/local/bin:/usr/bin:/bin",
                "BASH_ENV", "/opt/planted/rc",
                "ENV", "/opt/planted/rc"), files);

        assertThat(run.exitCode()).as(run.output()).isZero();
        // BASH_ENV must not be sourced by the script's own bash either (the image CMD starts it with env -u BASH_ENV -u ENV).
        assertThat(run.output()).doesNotContain("PLANTED_TOOL_RAN").doesNotContain("BASH_ENV_RAN")
                .contains("probe tool=psql path=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash_env=unset env=unset");
        assertThat(run.resultLine()).startsWith("db_backup_result status=ok ");
    }

    @Test
    @DisplayName("a lost reply for an object that is NOT there (HEAD 404) is still retried, then succeeds")
    void should_retry_when_replyLostAndObjectAbsent() throws Exception {
        Run run = runBackup(Map.of("SHIM_FAULT", "dropped"), curlShim());

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("upload attempt 1 http=000").contains("upload attempt 2 http=200")
                .doesNotContain("confirmed by HEAD");
        assertThat(keys(BACKUP_BUCKET)).hasSize(1);
    }

    @Test
    @DisplayName("storage answering 503 to every attempt: exactly three upload attempts, then error, nothing stored")
    void should_giveUpAfterThreeAttempts_when_storageAlwaysAnswers503() throws Exception {
        Run run = runBackup(Map.of("SHIM_FAULT", "unavailable", "SHIM_PUT_N", "all"), curlShim());

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.output()).contains("upload attempt 1 http=503", "upload attempt 2 http=503", "upload attempt 3 http=503",
                "db_backup: upload failed").doesNotContain("upload attempt 4");
        assertThat(run.resultLine()).startsWith("db_backup_result status=error ");
        assertThat(keys(BACKUP_BUCKET)).isEmpty();
        assertThat(checkInStatuses()).containsExactly("in_progress", "error");
        assertNoLeak(run.output());
    }

    @Test
    @DisplayName("a weekly copy answered 200 without <CopyObjectResult> is not trusted: run ends error, no weekly/ object")
    void should_failRun_when_copyAnswers200WithoutCopyObjectResult() throws Exception {
        Run run = runBackup(Map.of("SHIM_FAULT", "fake200", "SHIM_PUT_N", "1", "BACKUP_FORCE_WEEKLY", "1"), curlShim());

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.output()).contains("copy attempt 1 http=200", "weekly copy was not confirmed by the storage service")
                .doesNotContain("copy attempt 2");
        assertThat(run.resultLine()).startsWith("db_backup_result status=error ").contains(" weekly=0 ");
        assertThat(keys(BACKUP_BUCKET)).hasSize(1).allMatch(k -> k.startsWith("daily/"));
        assertThat(checkInStatuses()).containsExactly("in_progress", "error");
        assertNoLeak(run.output());
    }

    @Test
    @DisplayName("deadline: a hung pg_dump is cut at the remaining deadline; the run ends error with a check-in, far under budget")
    void should_endErrorWithCheckInUnderBudget_when_pgDumpHangsPastDeadline() throws Exception {
        byte[] hang = """
                #!/bin/sh
                sleep 90
                """.getBytes(StandardCharsets.UTF_8);

        Run run = runBackup(Map.of("BACKUP_DEADLINE_S", "12"), Map.of("/usr/local/sbin/pg_dump", Transferable.of(hang, 0755)));

        assertThat(run.exitCode()).isEqualTo(1);
        String result = run.resultLine();
        assertThat(result).startsWith("db_backup_result status=error ");
        Matcher duration = Pattern.compile("duration_s=(\\d+)").matcher(result);
        assertThat(duration.find()).isTrue();
        assertThat(Integer.parseInt(duration.group(1))).as("seconds: deadline 12 + kill grace, not the 90 s hang").isLessThan(30);
        assertThat(keys(BACKUP_BUCKET)).isEmpty();
        assertThat(checkInStatuses()).containsExactly("in_progress", "error");
        assertNoLeak(run.output());
    }

    @Test
    @DisplayName("pg_dump, psql and age never inherit the R2 secret or the check-in URL")
    void should_notExposeSecretsToTools_when_backupRuns() {
        byte[] probe = ("""
                #!/bin/sh
                n="$(basename "$0")"
                if env | grep -qE '^(BACKUP_R2_SECRET_ACCESS_KEY|BACKUP_R2_ACCESS_KEY_ID|BACKUP_SENTRY_CRON_URL)='; then v=1; else v=0; fi
                echo "probe tool=$n inherited_secret=$v" > /proc/1/fd/1 2>/dev/null || true
                PATH="${PATH#/usr/local/sbin:}"
                exec "$n" "$@"
                """).getBytes(StandardCharsets.UTF_8);
        Map<String, Transferable> shims = Map.of(
                "/usr/local/sbin/pg_dump", Transferable.of(probe, 0755),
                "/usr/local/sbin/psql", Transferable.of(probe, 0755),
                "/usr/local/sbin/age", Transferable.of(probe, 0755));

        Run run = runBackup(Map.of(), shims);

        assertThat(run.exitCode()).as(run.output()).isZero();
        for (String tool : List.of("pg_dump", "psql", "age")) {
            assertThat(run.output()).contains("probe tool=" + tool + " inherited_secret=0");
        }
        assertThat(run.output()).doesNotContain("inherited_secret=1");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private record Run(int exitCode, String output) {
        String resultLine() {
            Matcher m = RESULT_LINE.matcher(output);
            assertThat(m.find()).as("a db_backup_result line in:\n" + output).isTrue();
            String line = m.group();
            assertThat(m.find()).as("exactly one db_backup_result line").isFalse();
            return line;
        }
    }

    private record OneShot(int exitCode, String output) {
    }

    private static Run runBackup(Map<String, String> overrides) {
        return runBackup(overrides, Map.of());
    }

    private static Run runBackup(Map<String, String> overrides, Map<String, Transferable> files) {
        Map<String, String> env = new HashMap<>();
        env.put("DATABASE_URL", "postgresql://backup_src:" + DB_PASSWORD + "@pgsrc:5432/beautica?sslmode=disable");
        env.put("BACKUP_R2_ENDPOINT", "http://s3gw:7070");
        env.put("BACKUP_R2_ACCESS_KEY_ID", S3_ACCESS_KEY);
        env.put("BACKUP_R2_SECRET_ACCESS_KEY", S3_SECRET_KEY);
        env.put("BACKUP_R2_BUCKET", BACKUP_BUCKET);
        env.put("BACKUP_AGE_RECIPIENT", ageRecipient);
        env.put("BACKUP_SENTRY_CRON_URL", "http://sentrystub:8080" + SENTRY_PATH + SENTRY_KEY + "/");
        env.putAll(overrides);
        env.values().removeIf(String::isEmpty);
        OneShot result = runOneShot(null, env, files);
        return new Run(result.exitCode(), result.output());
    }

    /** Runs the backup image to completion (image ENTRYPOINT unless {@code entrypoint} is given). */
    private static OneShot runOneShot(String[] entrypoint, Map<String, String> env, Map<String, Transferable> files) {
        GenericContainer<?> container = new GenericContainer<>(BACKUP_IMAGE)
                .withNetwork(NETWORK)
                .withExtraHost(FAKE_EU_HOST, "127.0.0.1")
                .withEnv(env)
                .withStartupCheckStrategy(new RunToCompletion(Duration.ofMinutes(4)));
        if (entrypoint != null) {
            container.withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(entrypoint));
        }
        files.forEach((path, content) -> container.withCopyToContainer(content, path));
        try {
            container.start();
            return new OneShot(container.getCurrentContainerInfo().getState().getExitCodeLong().intValue(), container.getLogs());
        } finally {
            container.stop();
        }
    }

    /** Succeeds once the container has exited, whatever its exit code (the tests assert on it). */
    private static final class RunToCompletion extends StartupCheckStrategy {

        RunToCompletion(Duration timeout) {
            withTimeout(timeout);
        }

        @Override
        public StartupStatus checkStartupState(DockerClient dockerClient, String containerId) {
            InspectContainerResponse.ContainerState state = dockerClient.inspectContainerCmd(containerId).exec().getState();
            return DockerStatus.isContainerStopped(state) ? StartupStatus.SUCCESSFUL : StartupStatus.NOT_YET_KNOWN;
        }
    }

    private static void assertNoLeak(String output) {
        assertThat(output)
                .doesNotContain(S3_ACCESS_KEY, S3_SECRET_KEY, DB_PASSWORD, SENTRY_KEY)
                .doesNotContain("s3gw", "pgsrc", "sentrystub")
                .doesNotContain("http://", "https://");
    }

    private static List<String> keys(String bucket) {
        return s3.listObjectsV2(b -> b.bucket(bucket)).contents().stream().map(S3Object::key).sorted().toList();
    }

    /** Check-in statuses in arrival order (WireMock lists newest first). */
    private static List<String> checkInStatuses() throws Exception {
        JsonNode requests = JSON.readTree(wiremock("GET", "/__admin/requests", null)).get("requests");
        List<String> statuses = new ArrayList<>();
        for (JsonNode r : requests) {
            Matcher m = STATUS_PARAM.matcher(r.get("request").get("url").asText());
            if (m.find()) {
                statuses.add(0, m.group(1));
            }
        }
        return statuses;
    }

    private static void stubCheckIn(int status) throws Exception {
        wiremock("POST", "/__admin/mappings", """
                {"request":{"method":"GET","urlPathPattern":"/cron/.*"},"response":{"status":%d}}""".formatted(status));
    }

    private static String wiremock(String method, String path, String body) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://" + sentryStub.getHost() + ":" + sentryStub.getMappedPort(8080) + path))
                .timeout(Duration.ofSeconds(10));
        req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            req.header("Content-Type", "application/json");
        }
        return HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    /** A role whose password holds a literal backslash, to prove DATABASE_URL decoding leaves it alone. */
    private static void createBackslashRole() throws SQLException {
        try (Connection c = DriverManager.getConnection(source.getJdbcUrl(), source.getUsername(), source.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + BACKSLASH_USER + " LOGIN PASSWORD '" + BACKSLASH_PASSWORD + "'");
            s.execute("GRANT pg_read_all_data TO " + BACKSLASH_USER);
        }
    }

    /** Env of a run that is valid for the prod bucket except for what a test overrides. */
    private static Map<String, String> prodEnv(Map<String, String> overrides) {
        Map<String, String> env = new HashMap<>();
        env.put("BACKUP_R2_BUCKET", PROD_BUCKET);
        env.put("BACKUP_R2_ENDPOINT", "https://" + FAKE_EU_HOST);
        env.putAll(overrides);
        return env;
    }

    private static Map<String, Transferable> pinFile(String recipient) {
        return Map.of(PIN_FILE, Transferable.of(("# test pin\n" + recipient + "\n").getBytes(StandardCharsets.UTF_8), 0644));
    }

    /**
     * A {@code curl} shim in front of the image's curl that injects ONE storage fault into the Nth upload PUT
     * (SHIM_PUT_N, default 0 = the first; {@code all} = every PUT). SHIM_FAULT: {@code unavailable} answers 503 without
     * sending anything; {@code fake200} answers 200 with an empty body without sending anything;
     * {@code lost} really sends the request (the object is stored) but reports 000 / exit 28, as a timeout after the
     * server applied the write would; {@code dropped} reports 000 without sending anything. HEAD, GET, copies of
     * other numbers and the Sentry check-in pass straight through to the real curl.
     *
     * <p>Every storage request (aws-sigv4 config) is also summarised on the container log as one {@code shimcfg ...}
     * line: kind (body / copy / head), whether a {@code speed-limit} is set, and the {@code max-time}. No URL, key or
     * credential is echoed, so {@link #assertNoLeak} still holds.
     */
    private static Map<String, Transferable> curlShim() {
        byte[] shim = ("""
                #!/bin/sh
                cfg="$(cat)"
                PATH="${PATH#/usr/local/sbin:}"
                real() { printf '%s\\n' "$cfg" | curl "$@"; }
                case "$cfg" in
                  *aws-sigv4*)
                    kind=other
                    case "$cfg" in *upload-file*) kind=body ;; esac
                    case "$cfg" in *x-amz-copy-source*) kind=copy ;; esac
                    if printf '%s\\n' "$cfg" | grep -qx head; then kind=head; fi
                    sl=0; case "$cfg" in *speed-limit*|*speed-time*) sl=1 ;; esac
                    mt="$(printf '%s\\n' "$cfg" | sed -n 's/^max-time = //p')"
                    echo "shimcfg kind=$kind speed_floor=$sl max_time=$mt" > /proc/1/fd/1 2>/dev/null || true ;;
                esac
                case "$cfg" in
                  *aws-sigv4*'request = "PUT"'*) ;;
                  *) real "$@"; exit $? ;;
                esac
                n="$(cat /tmp/shim.n 2>/dev/null || echo 0)"
                echo $((n + 1)) > /tmp/shim.n
                if [ "${SHIM_PUT_N:-0}" != all ] && [ "$n" != "${SHIM_PUT_N:-0}" ]; then real "$@"; exit $?; fi
                case "$SHIM_FAULT" in
                  unavailable) printf 503; exit 0 ;;
                  fake200) printf 200; exit 0 ;;
                  dropped) printf 000; exit 28 ;;
                  lost) real "$@" >/dev/null; printf 000; exit 28 ;;
                esac
                real "$@"; exit $?
                """).getBytes(StandardCharsets.UTF_8);
        return Map.of("/usr/local/sbin/curl", Transferable.of(shim, 0755));
    }

    /** The {@code shimcfg ...} summaries the curl shim logged, in request order (prefix stripped). */
    private static List<String> shimConfigs(String output) {
        return output.lines().filter(l -> l.startsWith("shimcfg ")).map(l -> l.substring("shimcfg ".length())).toList();
    }

    private static void assertRefusedBeforeAnyWork(Run run, String namedVariable) throws Exception {
        assertThat(run.exitCode()).isNotZero();
        assertThat(run.output()).contains(namedVariable).doesNotContain("dumping").doesNotContain("uploading");
        assertThat(run.resultLine()).startsWith("db_backup_result status=error ");
        assertThat(keys(BACKUP_BUCKET)).isEmpty();
        assertThat(checkInStatuses()).isEmpty();
        assertNoLeak(run.output());
    }

    private static void seedUsers() throws SQLException {
        String hash = new BCryptPasswordEncoder(4).encode("test-password");
        try (Connection c = DriverManager.getConnection(source.getJdbcUrl(), source.getUsername(), source.getPassword());
             Statement s = c.createStatement()) {
            for (int i = 1; i <= 3; i++) {
                s.executeUpdate("INSERT INTO users (email, password_hash, role) VALUES ('backup-it-" + i + "@example.test', '" + hash + "', 'CLIENT')");
            }
        }
    }

    private static int flywayMaxVersion(String jdbcUrl, String user) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, DB_PASSWORD);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT max(version::int) FROM flyway_schema_history WHERE version IS NOT NULL")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Exact row count of every table in the public schema. */
    private static Map<String, Long> rowCounts(String jdbcUrl, String user) throws SQLException {
        Map<String, Long> counts = new TreeMap<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, DB_PASSWORD);
             Statement s = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String t : tables) {
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM \"" + t + "\"")) {
                    rs.next();
                    counts.put(t, rs.getLong(1));
                }
            }
        }
        assertThat(counts).as("tables found").isNotEmpty();
        return counts;
    }

    private static String randomToken(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        return RANDOM.ints(length, 0, alphabet.length()).mapToObj(i -> String.valueOf(alphabet.charAt(i))).collect(Collectors.joining());
    }
}

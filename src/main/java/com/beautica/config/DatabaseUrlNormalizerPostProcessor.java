package com.beautica.config;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Normalizes a libpq-style {@code DATABASE_URL} into a JDBC URL + credentials.
 *
 * <p><b>Railway.</b> Prod reads {@code DATABASE_URL=${{Postgres.DATABASE_URL}}}, i.e.
 * {@code postgresql://user:pass@<service>.railway.internal:5432/railway} (private network, no query
 * string). The {@code postgresql://} and {@code postgres://} shapes are rewritten to
 * {@code jdbc:postgresql://host:port/db?...} and the credentials are split into
 * {@code spring.datasource.username/password}.
 *
 * <p><b>TLS by default.</b> When the URL carries no {@code sslmode} and the host is not loopback
 * ({@code localhost}, {@code 127.0.0.1}, {@code ::1}), {@code sslmode=require} is appended, because
 * the pgjdbc default ({@code prefer}) silently falls back to plaintext. An explicit {@code sslmode}
 * always wins (an empty/blank {@code sslmode=} counts as absent and is replaced by the
 * default); outside prod a weak one ({@code disable}, {@code allow}, {@code prefer}, value matched
 * case-insensitively) on a non-loopback host is accepted but logged at WARN naming only the mode. Under the
 * {@code prod} profile the effective mode (explicit or injected) must be exactly the lowercase {@code require},
 * {@code verify-ca} or {@code verify-full} (pgjdbc's {@code SslMode.of} is case-sensitive, so {@code REQUIRE} would
 * fail at connect); anything else fails startup with a {@code ProdTransportViolationException}.
 *
 * <p><b>sslmode key hygiene.</b> Only a key that is RAW-exactly {@code sslmode} is recognised, because pgjdbc does not
 * decode keys. A pair whose key percent-decodes and case-folds to {@code sslmode} without being exactly that
 * ({@code %73slmode}, {@code SSLMODE}) would be ignored by pgjdbc (default {@code prefer}): prod fails startup, non-prod
 * drops it with a fixed-text WARN so the secure default is injected. More than one exact {@code sslmode} key: prod
 * fails startup; non-prod keeps the LAST (pgjdbc's {@code setProperty} semantics) and WARNs with fixed text.
 *
 * <p><b>Why not {@code verify-full}.</b> {@code require} encrypts but does not authenticate the
 * server. Railway Postgres (SSL template) issues a per-service certificate signed by a self-signed
 * root CA, with SAN {@code localhost} only and no CA distribution or rotation process, so
 * {@code verify-full} cannot succeed (at best {@code verify-ca} with an operator-pinned CA), and the
 * TCP proxy ({@code *.rlwy.net}) uses self-signed certificates. Source: Railway Station threads
 * "verify-full ssl mode on postgres databse", "PostgreSQL certificate SAN/CA prevents
 * sslmode=verify-full", "How to securely obtain a PostgreSQL public root CA without SSH?".
 * Therefore {@code require} stays the default. The application MUST use the private
 * {@code *.railway.internal} URL in prod (traffic never leaves Railway's private network); the
 * public proxy {@code *.rlwy.net} is for one-off operator access only. Under the {@code prod} profile the
 * host is an ALLOWLIST: only {@code *.railway.internal} is accepted; {@code *.rlwy.net}, any other public host,
 * loopback and {@code 127.x} all FAIL startup with an {@code IllegalStateException} (fixed text, no
 * host/URL/password) when the
 * {@code prod} profile is active (staging runs {@code prod,staging}, so it is covered too); in any other
 * profile only a WARN is logged for {@code *.rlwy.net}. Profiles are read from {@code environment.getActiveProfiles()} plus the
 * {@code spring.profiles.active} property (see {@code isProdProfileActive}). An operator who pins a CA can set {@code sslmode=verify-ca} (plus
 * {@code sslrootcert}) explicitly in the URL.
 *
 * <p>Raw {@code jdbc:} URLs are not inspected: outside prod they pass through unchanged (no checks, no
 * {@code sslmode} injected); in prod they are refused (fail closed), because pgjdbc's host grammar (multi-host
 * lists, userinfo, {@code host=}/{@code PGHOST=} params) is deliberately not mirrored. Railway always supplies the
 * {@code postgresql://} form. Hosts are compared lower-cased with trailing dots stripped.
 *
 * <p><b>Residual.</b> The private network uses {@code require} without server authentication because Railway
 * issues no CA; this is accepted at the platform level and tracked in the phase 365 doc.
 *
 * <p><b>Failure handling.</b> Outside prod, a URL that cannot be parsed, or has no parseable host (e.g. a host
 * containing an underscore, multi-host list, {@code @} in the host), is logged at WARN (exception class or a
 * fixed message only, never the URL or message, which may hold the password) and boot continues with the raw
 * value. In prod such a URL fails startup with a {@code ProdTransportViolationException} (fixed text; only that
 * type escapes). In prod, ANY non-{@code *.railway.internal} authority host, a {@code jdbc:} URL, or a host-override query key ({@code host},
 * {@code PGHOST}, {@code hostaddr}, {@code PGHOSTADDR}, case-insensitive) fails startup with a
 * {@code ProdTransportViolationException} (fixed text). Outside prod the proxy host only WARNs and the
 * host-override keys are dropped with a fixed-text WARN.
 *
 * <p><b>Query and user-info are handled RAW.</b> The query ({@code URI#getRawQuery}) is split on {@code &}/{@code =}
 * before any decoding; only the KEY is percent-decoded (once) to match the drop list and the
 * host-override keys case-insensitively (so {@code %68ost} is still {@code host}); the {@code sslmode} key is the
 * exception and is matched RAW-exactly, see above. Pairs are passed to
 * the JDBC URL unchanged so pgjdbc performs its single decode (a {@code %26} inside a value stays one parameter). For
 * the weak-{@code sslmode} WARN the value is decoded repeatedly until stable, so {@code %2564isable} counts as
 * {@code disable}. User-info ({@code URI#getRawUserInfo}) is split on the first literal {@code :} and the username and
 * password are then percent-decoded separately ({@code a%3Ab} is username {@code a:b}).
 *
 * <p><b>Database name.</b> The path is read RAW ({@code URI#getRawPath}, never percent-decoded) and must match
 * {@code ^/[A-Za-z0-9_.-]*$}; an empty path (no database) is accepted and yields a URL with no database segment, as
 * before. Otherwise {@code %3F}/{@code %26} would decode into a smuggled query ({@code ?host=evil}, {@code ?sslmode=disable})
 * that bypasses {@code filterQuery}. In prod a non-plain path fails startup with a {@code ProdTransportViolationException}
 * (fixed text, no path); outside prod a fixed-text WARN is logged and {@code DATABASE_URL} is left untouched.
 *
 * <p><b>Pooler caveat (planning cost).</b> Large bookability predicates
 * (e.g. {@code MasterRepository#findBookableIdsBySalonId}) cost ~7 ms to plan vs ~0.3 ms to
 * execute. pgjdbc server-prepares a statement after 5 uses (default {@code prepareThreshold=5}),
 * which hides that cost. Do not put a transaction-mode pooler (PgBouncer) in front of the database
 * with {@code prepareThreshold=0} without revisiting this: every request would pay the ~7 ms plan.
 */
public class DatabaseUrlNormalizerPostProcessor implements EnvironmentPostProcessor {

    private static final Set<String> LIBPQ_ONLY_PARAMS = Set.of("channel_binding", "sslnegotiation");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final String SSLMODE_PARAM = "sslmode";
    private static final String DEFAULT_SSLMODE = "sslmode=require";
    private static final String SERVER_ERROR_DETAIL_PARAM = "logServerErrorDetail";
    private static final String SERVER_ERROR_DETAIL_OFF = SERVER_ERROR_DETAIL_PARAM + "=false";
    private static final Set<String> WEAK_SSLMODES = Set.of("disable", "allow", "prefer");
    private static final Set<String> STRONG_SSLMODES = Set.of("require", "verify-ca", "verify-full");
    private static final Set<String> HOST_OVERRIDE_PARAMS = Set.of("host", "pghost", "hostaddr", "pghostaddr");
    private static final String PROD_PROFILE = "prod";
    private static final String RAILWAY_PUBLIC_PROXY_SUFFIX = ".rlwy.net";
    private static final String RAILWAY_PRIVATE_SUFFIX = ".railway.internal";
    private static final String PROD_UNPARSEABLE_MESSAGE =
            "prod requires a parseable single-host postgresql:// DATABASE_URL";

    /** Database name: raw (never percent-decoded) path of letters, digits, {@code _ . -} only. */
    private static final Pattern PLAIN_PATH = Pattern.compile("^/[A-Za-z0-9_.-]*$");
    private static final String PROD_PLAIN_DB_NAME_MESSAGE = "prod requires a plain database name in DATABASE_URL";

    private final Log log;

    /** Used by tests and direct instantiation; logs through the regular commons-logging factory. */
    public DatabaseUrlNormalizerPostProcessor() {
        this.log = LogFactory.getLog(DatabaseUrlNormalizerPostProcessor.class);
    }

    /** Used by Spring Boot: an EnvironmentPostProcessor runs before logging is initialised. */
    public DatabaseUrlNormalizerPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DatabaseUrlNormalizerPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String rawUrl = environment.getProperty("DATABASE_URL");
        if (rawUrl == null) {
            return;
        }
        boolean prod = isProdProfileActive(environment);
        try {
            if (rawUrl.startsWith("jdbc:")) {
                if (prod) {
                    // Fail closed: pgjdbc's host grammar (multi-host, userinfo, host=/PGHOST= params) is not mirrored here.
                    throw new ProdTransportViolationException("prod requires DATABASE_URL in postgresql:// form "
                            + "(Railway's ${{Postgres.DATABASE_URL}}); raw jdbc: URLs are refused");
                }
                return;
            }
            URI uri = URI.create(rawUrl);
            String host = uri.getHost();
            if (host == null) {
                if (prod) {
                    throw new ProdTransportViolationException(PROD_UNPARSEABLE_MESSAGE);
                }
                // Fixed message: never echo the URL (password) or the parser message.
                log.warn("DATABASE_URL could not be normalized: no parseable host");
                return;
            }
            enforceProdHostAllowlist(host, prod);
            int port = uri.getPort();
            // RAW path: getPath() percent-decodes, so %3F would smuggle a query (host=/sslmode=) past filterQuery.
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !PLAIN_PATH.matcher(path).matches()) {
                if (prod) {
                    throw new ProdTransportViolationException(PROD_PLAIN_DB_NAME_MESSAGE);
                }
                // Fixed message: never echo the URL or the path.
                log.warn("DATABASE_URL could not be normalized: database name is not plain");
                return;
            }
            String query = uri.getRawQuery();
            String userInfo = uri.getRawUserInfo();

            StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://");
            jdbcUrl.append(host);
            if (port > 0) jdbcUrl.append(':').append(port);
            if (path != null && !path.isEmpty()) jdbcUrl.append(path);

            String filtered = withDefaultSslMode(filterQuery(query, prod), host);
            enforceProdSslFloor(filtered, prod);
            warnOnInsecureTransport(filtered, host, prod);
            filtered = withServerErrorDetailOff(filtered, prod);
            jdbcUrl.append('?').append(filtered);

            Map<String, Object> props = new HashMap<>();
            props.put("spring.datasource.url", jdbcUrl.toString());

            if (userInfo != null) {
                // RAW user-info: split on the first literal ':' BEFORE decoding, so a %3A in the username survives.
                int sep = userInfo.indexOf(':');
                if (sep >= 0) {
                    props.put("spring.datasource.username", percentDecode(userInfo.substring(0, sep)));
                    props.put("spring.datasource.password", percentDecode(userInfo.substring(sep + 1)));
                } else {
                    props.put("spring.datasource.username", percentDecode(userInfo));
                }
            }

            environment.getPropertySources().addFirst(
                    new MapPropertySource("normalizedDatabaseUrl", props));
        } catch (ProdTransportViolationException ex) {
            // Deliberate fail-fast (prod transport violation): fixed-text message, safe to propagate.
            // Only this type is rethrown; any other IllegalStateException keeps the soft-WARN path.
            throw ex;
        } catch (Exception ex) {
            if (prod) {
                // Parse failure in prod must not boot with the raw value; fixed text, no cause (it embeds the URL).
                throw new ProdTransportViolationException(PROD_UNPARSEABLE_MESSAGE);
            }
            // Class name only: the message of a URI parse error embeds the raw URL, password included.
            log.warn("DATABASE_URL could not be normalized: " + ex.getClass().getSimpleName());
        }
    }

    /**
     * Prod allowlist: the only accepted host is the private Railway one ({@code *.railway.internal}).
     * Fixed texts, no host/URL/password.
     */
    private static void enforceProdHostAllowlist(String host, boolean prod) {
        if (!prod) return;
        String h = normalizeHost(host);
        if (h.endsWith(RAILWAY_PRIVATE_SUFFIX) && h.length() > RAILWAY_PRIVATE_SUFFIX.length()) return;
        if (h.endsWith(RAILWAY_PUBLIC_PROXY_SUFFIX)) {
            throw new ProdTransportViolationException("DATABASE_URL points at the Railway public proxy; "
                    + "the prod app must use the private *.railway.internal URL");
        }
        throw new ProdTransportViolationException(
                "prod requires the private Railway host (*.railway.internal) in DATABASE_URL");
    }

    /** Lower-cased (Locale.ROOT) host with trailing dots removed, for every host check. */
    private static String normalizeHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        int end = h.length();
        while (end > 0 && h.charAt(end - 1) == '.') end--;
        return h.substring(0, end);
    }

    private static final class ProdTransportViolationException extends IllegalStateException {
        ProdTransportViolationException(String message) {
            super(message);
        }
    }

    /**
     * Outside prod: appends {@code logServerErrorDetail=false} unless the URL already names that key (an explicit
     * value wins, like {@code sslmode}). In prod the value is FORCED off (cycle 4, L-NEW-1): every existing
     * {@code logServerErrorDetail} param (key matched case-insensitively and percent-decoded) is dropped and
     * {@code =false} appended, so an operator cannot re-enable row values in log stack traces. Overriding rather
     * than refusing: the setting is a log-hygiene default, not a transport guarantee like {@link #enforceProdSslFloor}.
     * pgjdbc otherwise copies the server's {@code Detail:} line ({@code Failing row contains (...)}, {@code Key
     * (x)=(value)}) into {@code PSQLException#getMessage()}, which Hibernate's {@code SqlExceptionHelper} logs: row
     * values (names, booking notes) must never reach logs or Sentry. The structured constraint name
     * ({@code ServerErrorMessage#getConstraint}) is unaffected.
     */
    private static String withServerErrorDetailOff(String query, boolean prod) {
        String base = query == null ? "" : query;
        if (prod) {
            java.util.List<String> kept = new java.util.ArrayList<>();
            for (String param : base.split("&")) {
                if (param.isEmpty()) continue;
                if (SERVER_ERROR_DETAIL_PARAM.equalsIgnoreCase(fullyDecode(rawKey(param)))) continue;
                kept.add(param);
            }
            kept.add(SERVER_ERROR_DETAIL_OFF);
            return String.join("&", kept);
        }
        if (!base.isEmpty()) {
            for (String param : base.split("&")) {
                if (SERVER_ERROR_DETAIL_PARAM.equals(rawKey(param))) return base;
            }
        }
        return base.isEmpty() ? SERVER_ERROR_DETAIL_OFF : base + "&" + SERVER_ERROR_DETAIL_OFF;
    }

    private static String withDefaultSslMode(String query, String host) {
        if (isLoopback(host)) return query;
        if (sslModeValue(query) != null) return query;
        return query.isEmpty() ? DEFAULT_SSLMODE : query + "&" + DEFAULT_SSLMODE;
    }

    /**
     * Active profiles are resolved by the time this runs: the class is not {@code Ordered}, so it sorts at
     * lowest precedence, after {@code ConfigDataEnvironmentPostProcessor} has applied
     * {@code spring.profiles.active} (including {@code SPRING_PROFILES_ACTIVE}) via {@code setActiveProfiles}.
     * The property is read as well, as a defence against an ordering change.
     */
    private static boolean isProdProfileActive(ConfigurableEnvironment environment) {
        if (Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE)) return true;
        String prop = environment.getProperty("spring.profiles.active");
        if (prop == null) return false;
        return Arrays.stream(prop.split(",")).map(String::trim).anyMatch(PROD_PROFILE::equals);
    }

    private void warnOnInsecureTransport(String query, String host, boolean prod) {
        if (isLoopback(host)) return;
        String mode = sslModeValue(query);
        String decodedMode = mode == null ? null : fullyDecode(mode).toLowerCase(Locale.ROOT);
        if (decodedMode != null && WEAK_SSLMODES.contains(decodedMode)) {
            log.warn("DATABASE_URL sslmode=" + decodedMode
                    + " on a non-loopback host; connection is not protected against eavesdropping");
        }
        if (normalizeHost(host).endsWith(RAILWAY_PUBLIC_PROXY_SUFFIX)) {
            // prod is already rejected by enforceProdHostAllowlist; this is the non-prod WARN.
            log.warn("DATABASE_URL points at the Railway public TCP proxy (*.rlwy.net); use the private "
                    + "*.railway.internal URL in production. The proxy is for one-off operator access only.");
        }
    }

    private static boolean isLoopback(String host) {
        return LOOPBACK_HOSTS.contains(normalizeHost(host));
    }

    /**
     * RAW value of the LAST {@code sslmode} param (the RAW key must equal {@code sslmode} exactly: pgjdbc does not
     * decode keys, and its {@code setProperty} makes the last duplicate win), or {@code null} if absent. The value is
     * NOT decoded: pgjdbc decodes it itself.
     */
    private static String sslModeValue(String query) {
        if (query == null || query.isEmpty()) return null;
        String found = null;
        for (String param : query.split("&")) {
            int eq = param.indexOf('=');
            String key = eq >= 0 ? param.substring(0, eq) : param;
            if (SSLMODE_PARAM.equals(key)) found = eq >= 0 ? param.substring(eq + 1) : "";
        }
        return found;
    }

    /** Percent-decodes once; unlike {@link URLDecoder} on its own, {@code +} stays {@code +}. */
    private static String percentDecode(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** Decodes repeatedly (bounded) until stable, so a double-encoded {@code %2564isable} reads as {@code disable}. */
    private static String fullyDecode(String s) {
        String current = s;
        for (int i = 0; i < 5; i++) {
            String next = percentDecode(current);
            if (next.equals(current)) break;
            current = next;
        }
        return current;
    }

    private String filterQuery(String query, boolean prod) {
        if (query == null || query.isEmpty()) return "";
        String[] params = query.split("&");
        int exactSslModeCount = 0;
        int lastExactSslMode = -1;
        for (int i = 0; i < params.length; i++) {
            if (SSLMODE_PARAM.equals(rawKey(params[i]))) {
                exactSslModeCount++;
                lastExactSslMode = i;
            }
        }
        if (exactSslModeCount > 1) {
            if (prod) {
                throw new ProdTransportViolationException(
                        "DATABASE_URL carries more than one sslmode parameter; refusing to start in prod");
            }
            log.warn("DATABASE_URL carries more than one sslmode parameter; the last one is used");
        }
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (int i = 0; i < params.length; i++) {
            String param = params[i];
            String rawKey = rawKey(param);
            int eq = param.indexOf('=');
            // Key decoded once for matching only; the pair itself passes through RAW (pgjdbc decodes it).
            String key = percentDecode(rawKey);
            if (HOST_OVERRIDE_PARAMS.contains(key.toLowerCase(Locale.ROOT))) {
                // pgjdbc lets these override the authority host, which is the only host checked.
                if (prod) {
                    throw new ProdTransportViolationException(
                            "DATABASE_URL carries a host-override query parameter; refusing to start in prod");
                }
                log.warn("DATABASE_URL host-override query parameter dropped");
                continue;
            }
            if (SSLMODE_PARAM.equals(rawKey)) {
                if (i != lastExactSslMode) continue; // duplicate (non-prod only): pgjdbc's last one wins
                if (eq < 0 || percentDecode(param.substring(eq + 1)).isBlank()) {
                    continue; // empty sslmode counts as absent, so the secure default is injected
                }
            } else if (SSLMODE_PARAM.equals(key.toLowerCase(Locale.ROOT))) {
                // pgjdbc does not decode keys: an encoded/case-variant key is ignored by it and falls back to "prefer".
                if (prod) {
                    throw new ProdTransportViolationException(
                            "DATABASE_URL carries an encoded or case-variant sslmode key; refusing to start in prod");
                }
                log.warn("DATABASE_URL encoded or case-variant sslmode parameter dropped");
                continue;
            }
            if (!LIBPQ_ONLY_PARAMS.contains(key)) kept.add(param);
        }
        return String.join("&", kept);
    }

    private static String rawKey(String param) {
        int eq = param.indexOf('=');
        return eq >= 0 ? param.substring(0, eq) : param;
    }

    /** Prod transport floor: the effective sslmode (explicit or injected) must be exactly lowercase require, verify-ca or verify-full. */
    private static void enforceProdSslFloor(String query, boolean prod) {
        if (!prod) return;
        String mode = sslModeValue(query);
        if (mode == null || !STRONG_SSLMODES.contains(percentDecode(mode))) {
            throw new ProdTransportViolationException("prod requires sslmode=require or stronger");
        }
    }
}

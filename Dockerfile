# ─── Stage 1: build ───────────────────────────────────────────────────────────
# Gradle is preinstalled in the image so the build never downloads the wrapper distribution:
# services.gradle.org redirects to GitHub release assets, and a slow GitHub first byte (up to 27s)
# blew the wrapper's networkTimeout=10000 and failed Railway deploys. The tag's Gradle version
# MUST match distributionUrl in gradle/wrapper/gradle-wrapper.properties (guarded by RailwayConfigTest).
# Digest pinned on purpose (multi-arch index, so linux/amd64 resolves): update it TOGETHER with the wrapper version.
FROM gradle:8.10-jdk21-alpine@sha256:8721f7ba7260103b5157925e2666c6fd611e7741905fb032dfa8dbd8d12f4b2e AS builder

WORKDIR /app

# Copy build files first for layer caching (no wrapper: gradle/ holds only the wrapper, no version catalog)
COPY settings.gradle.kts build.gradle.kts ./

# Download dependencies (cached unless build files change)
RUN gradle dependencies --no-daemon --quiet

# Copy source and build
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ─── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Non-root user for security
RUN addgroup -S beautica && adduser -S beautica -G beautica
USER beautica

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

# IMPORTANT: Do NOT change the GC flags below without a Railway plan upgrade.
# History: ZGC was tried twice (Phase 9 and Phase 12 audit) and caused a silent OOM crash-loop
# on a 512 MB Railway container in both cases — fixed by 32a3799, re-introduced by Phase 12.
# SerialGC + explicit heap cap proved stable under that old 512 MB assumption and is kept; the
# Phase 367 memory budget below supersedes the 512 MB premise (Hobby has no 512 MB cap).
# Memory budget (Phase 367 audit): heap 256m + metaspace <=192m + code cache <=64m = 512m of JVM-managed
# regions BEFORE thread stacks, direct buffers and GC/native overhead. That would not fit a hard 512 MB
# container, but Railway Hobby does not impose one: docs.railway.com/pricing/plans lists Hobby at up to
# 48 GB RAM / 48 vCPU per service (usage-billed ceiling, not a 512 MB cap), so the ceilings below are
# MAXIMA, not reservations, and real metaspace use (a few tens of MB for this app) leaves ample headroom.
# MaxMetaspaceSize is therefore deliberately NOT lowered. If a per-service memory limit <= 512 MB is ever
# set in the Railway dashboard, lower MaxMetaspaceSize to ~160m and re-measure (jcmd <pid> VM.metaspace).
ENTRYPOINT ["java", \
  "-XX:+UseSerialGC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Xms128m", \
  "-Xmx256m", \
  "-XX:MaxMetaspaceSize=192m", \
  "-XX:ReservedCodeCacheSize=64m", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

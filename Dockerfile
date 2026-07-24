# ─── Stage 1: build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Gradle wrapper and build files first for layer caching
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./

# Download dependencies (cached unless build files change)
RUN ./gradlew dependencies --no-daemon --quiet

# Copy source and build
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ─── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Non-root user for security
RUN addgroup -S beautica && adduser -S beautica -G beautica
USER beautica

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

# IMPORTANT: Do NOT change the GC flags below without a Railway plan upgrade.
# ZGC was tried twice (Phase 9 and Phase 12 audit) and caused a silent OOM crash-loop
# on Railway 512 MB in both cases — fixed by 32a3799, re-introduced by Phase 12.
# SerialGC + explicit heap cap is the only proven-stable config on 512 MB.
ENTRYPOINT ["java", \
  "-XX:+UseSerialGC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Xms128m", \
  "-Xmx256m", \
  "-XX:MaxMetaspaceSize=192m", \
  "-XX:ReservedCodeCacheSize=64m", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

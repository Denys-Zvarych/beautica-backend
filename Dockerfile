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

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseZGC", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]

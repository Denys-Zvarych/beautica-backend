import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    jacoco
}

group = "com.beautica"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

// Override Spring Boot BOM's Testcontainers 1.19.8 with 1.20.4.
// Reason: docker-java 3.3.6 (TC 1.19.8) negotiates Docker API version 1.32,
// which Docker Engine 25+ rejects (minimum supported is 1.40).
// TC 1.20.x ships docker-java 3.4.x that defaults to API 1.41+.
dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.20.4")
    }
}

dependencies {
    // Web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Data
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    // Flyway
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Security
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Rate limiting
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("com.github.ben-manes.caffeine:caffeine")

    // JWT (JJWT 0.12)
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Email
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Cloudflare R2 (S3-compatible)
    implementation(platform("software.amazon.awssdk:bom:2.25.70"))
    implementation("software.amazon.awssdk:s3")

    // Firebase Admin SDK (push notifications)
    implementation("com.google.firebase:firebase-admin:9.3.0")

    // API docs
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // Observability
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.junit.platform:junit-platform-launcher")
    // Apache HttpClient 5 — required for TestRestTemplate to support PATCH
    // (JDK HttpURLConnection rejects PATCH; HttpComponentsClientHttpRequestFactory does not)
    testImplementation("org.apache.httpcomponents.client5:httpclient5")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Docker Engine 25+ requires minimum API version 1.40.
    // Testcontainers 1.20.x ships a shaded docker-java that hardcodes VERSION_1_32 in
    // DockerClientProviderStrategy.getDockerClient() unless "api.version" is set in the
    // JVM system properties. The shaded docker-java does NOT read DOCKER_API_VERSION as
    // an environment variable — it only reads it as a JVM system property keyed "api.version".
    // -Dapi.version=1.41 causes createDefaultConfigBuilder() to return a non-UNKNOWN version,
    // which makes DockerClientProviderStrategy skip the unconditional VERSION_1_32 override.
    //
    // -XX:+EnableDynamicAgentLoading  : JVM-supported opt-in for Mockito/Byte Buddy's inline
    //                                   mock maker. Without it, each test run emits a
    //                                   "A Java agent has been loaded dynamically" warning to
    //                                   stderr which is unformatted and pollutes the test log.
    // -Xshare:off                     : disables CDS (class-data sharing). The JDK default
    //                                   archive is built with the boot classpath only, so when
    //                                   test agents alter the classpath the JVM prints
    //                                   "Sharing is only supported for boot loader classes"
    //                                   to stderr. Turning CDS off is cheaper than whitelisting
    //                                   every attached agent and removes the warning entirely.
    jvmArgs(
        "-Dapi.version=1.41",
        "-XX:+EnableDynamicAgentLoading",
        "-Xshare:off"
    )

    val testLogLevel = (project.findProperty("testLogLevel") as String?) ?: "TRACE"
    val testRootLogLevel = (project.findProperty("testRootLogLevel") as String?) ?: "INFO"
    systemProperty("test.log.level", testLogLevel)
    systemProperty("test.root.log.level", testRootLogLevel)

    testLogging {
        // Lifecycle logs come from the SLF4J TestSuiteLoggerListener / TestIntentLoggerExtension,
        // which write through Logback's ConsoleAppender (System.out). We must forward the
        // forked test JVM's standard streams so those SLF4J banners reach the Gradle console —
        // otherwise every test run appears silent. Raw framework noise (Spring Boot banner,
        // Hibernate SQL, HikariCP startup, etc.) is kept quiet via logback-test.xml, not by
        // suppressing stdout here. backend-verify.sh parses PASSED/FAILED/SKIPPED lines to count results.
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

val coverageVerification = tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value   = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()   // 80% line coverage minimum
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                value   = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()   // 70% branch coverage minimum
            }
        }
    }
    // Exclude generated code, config classes, and DTOs from coverage measurement.
    // These classes contain no business logic that can meaningfully fail.
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "**/config/**",
                    "**/dto/**",
                    "**/entity/**",
                    "**/enums/**",
                    "**/*Application*",
                    "**/BeauticaApplication*"
                )
            }
        })
    )
}

// Enforce coverage thresholds as part of the standard check lifecycle so CI fails
// automatically when coverage drops below the minimum without needing an explicit task flag.
tasks.check {
    dependsOn(coverageVerification)
}

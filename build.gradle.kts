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

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
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

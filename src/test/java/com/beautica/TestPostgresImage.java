package com.beautica;

import org.testcontainers.utility.DockerImageName;

/**
 * Single source of truth for the PostgreSQL image every Testcontainers test runs on. It must stay on
 * the same major as prod (Railway {@code postgres-ssl:18}) and {@code docker/local/docker-compose.yml};
 * {@code PostgresImagePinConsistencyTest} enforces both.
 */
public final class TestPostgresImage {

    public static final String IMAGE = "postgres:18-alpine";

    private TestPostgresImage() {
    }

    public static DockerImageName dockerImageName() {
        return DockerImageName.parse(IMAGE);
    }
}

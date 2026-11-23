package com.chzzk;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * One MinIO container shared by all S3-mode tests in the JVM. MinIO stopped publishing images on Docker Hub, so this
 * uses the frozen Bitnami legacy build (the same image the kind manifests use).
 */
public final class MinioTestContainer {

    private static final String USER = "minioadmin";
    private static final String PASSWORD = "minioadmin-test-secret";

    private static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("bitnamilegacy/minio:2025.7.23-debian-12-r5"))
                    .withEnv("MINIO_ROOT_USER", USER)
                    .withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    static {
        MINIO.start();
    }

    private MinioTestContainer() {
    }

    public static String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    public static String accessKey() {
        return USER;
    }

    public static String secretKey() {
        return PASSWORD;
    }
}

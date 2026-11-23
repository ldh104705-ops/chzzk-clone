package com.chzzk;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** One Redis 7 container shared by all Redis-mode tests in the JVM (started on first use, removed by Ryuk at exit). */
public final class RedisTestContainer {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private RedisTestContainer() {
    }

    public static String host() {
        return REDIS.getHost();
    }

    public static int port() {
        return REDIS.getMappedPort(6379);
    }
}

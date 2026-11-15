package com.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Verifies V2 and V3 against a database that already holds V1 data, and that its CHECK constraints bite. */
@Testcontainers
class LiveMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static long count(String where) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM broadcast WHERE " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void v2KeepsV1RowsAndWidensTheConstraints() throws Exception {
        flyway("1").migrate();
        exec("INSERT INTO broadcast (title, type, status, video_url) VALUES ('old', 'RECORDED', 'ENDED', 'https://example.com/a.mp4')");
        // V1 does not know LIVE yet.
        assertThatThrownBy(() -> exec("INSERT INTO broadcast (title, type, status) VALUES ('x', 'LIVE', 'LIVE')"))
                .isInstanceOf(SQLException.class);

        flyway("2").migrate();

        assertThat(count("title = 'old' AND started_at IS NULL AND ended_at IS NULL AND broadcaster_key_hash IS NULL")).isEqualTo(1);
        exec("INSERT INTO broadcast (title, type, status, broadcaster_key_hash) VALUES ('ready', 'LIVE', 'READY', 'abc')");
        exec("INSERT INTO broadcast (title, type, status, broadcaster_key_hash, started_at) VALUES ('live', 'LIVE', 'LIVE', 'abc', now())");
        exec("INSERT INTO broadcast (title, type, status, broadcaster_key_hash, ended_at) VALUES ('end', 'LIVE', 'ENDED', 'abc', now())");
        assertThat(count("type = 'LIVE'")).isEqualTo(3);

        // The constraints still reject invalid rows.
        assertThatThrownBy(() -> exec("INSERT INTO broadcast (title, type, status, broadcaster_key_hash) VALUES ('t', 'LIVE', 'PAUSED', 'abc')"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> exec("INSERT INTO broadcast (title, type, status, broadcaster_key_hash) VALUES ('t', 'OTHER', 'LIVE', 'abc')"))
                .isInstanceOf(SQLException.class);
        // A live broadcast needs a key hash and may not carry a video source.
        assertThatThrownBy(() -> exec("INSERT INTO broadcast (title, type, status) VALUES ('t', 'LIVE', 'LIVE')"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> exec("INSERT INTO broadcast (title, type, status, broadcaster_key_hash, video_url) VALUES ('t', 'LIVE', 'LIVE', 'abc', 'https://e.com/a.mp4')"))
                .isInstanceOf(SQLException.class);
        // A recorded broadcast must be ENDED and still needs exactly one video source.
        assertThatThrownBy(() -> exec("INSERT INTO broadcast (title, type, status, video_url) VALUES ('t', 'RECORDED', 'LIVE', 'https://e.com/a.mp4')"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> exec("INSERT INTO broadcast (title, type, status) VALUES ('t', 'RECORDED', 'ENDED')"))
                .isInstanceOf(SQLException.class);

        // ---- V3: users, broadcast owner, comments; the broadcaster key column is gone.
        flyway("latest").migrate();
        assertThat(count("title = 'old' AND owner_id IS NULL")).isEqualTo(1); // pre-existing rows survive, ownerless
        assertThatThrownBy(() -> exec("SELECT broadcaster_key_hash FROM broadcast")).isInstanceOf(SQLException.class);
        exec("INSERT INTO broadcast (title, type, status) VALUES ('keyless', 'LIVE', 'READY')"); // no key hash any more
        assertThatThrownBy(() -> exec("INSERT INTO broadcast (title, type, status, video_url) VALUES ('t', 'LIVE', 'READY', 'https://e.com/a.mp4')"))
                .isInstanceOf(SQLException.class); // a live broadcast still may not carry a video source

        exec("INSERT INTO users (username, password_hash, nickname) VALUES ('alice', 'hash', '앨리스')");
        assertThatThrownBy(() -> exec("INSERT INTO users (username, password_hash, nickname) VALUES ('alice', 'h', 'dup')"))
                .isInstanceOf(SQLException.class); // unique username
        assertThatThrownBy(() -> exec("INSERT INTO users (username, password_hash, nickname) VALUES ('Bob', 'h', 'b')"))
                .isInstanceOf(SQLException.class); // must be lower-case
        assertThatThrownBy(() -> exec("INSERT INTO users (username, password_hash, nickname) VALUES ('carol', 'h', '   ')"))
                .isInstanceOf(SQLException.class); // blank nickname

        exec("UPDATE broadcast SET owner_id = (SELECT id FROM users WHERE username = 'alice') WHERE title = 'keyless'");
        String insertComment = "INSERT INTO comment (broadcast_id, author_id, content) "
                + "SELECT b.id, u.id, '%s' FROM broadcast b, users u WHERE b.title = 'keyless' AND u.username = 'alice'";
        exec(insertComment.formatted("hello"));
        assertThatThrownBy(() -> exec(insertComment.formatted("   "))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> exec(insertComment.formatted("x".repeat(501)))).isInstanceOf(SQLException.class);
        assertThat(scalar("SELECT count(*) FROM comment")).isEqualTo(1);

        exec("DELETE FROM broadcast WHERE title = 'keyless'"); // comments go with their broadcast
        assertThat(scalar("SELECT count(*) FROM comment")).isZero();
    }

    private static long scalar(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement();
             var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}

package honey.road.node.monitoring;

import com.zaxxer.hikari.HikariDataSource;
import honey.road.node.it.AbstractNodeIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The real sections of a node snapshot (design 7). Here without NATS and without a log file. */
@SuppressWarnings("unchecked")
class DiagnosticsSnapshotIT extends AbstractNodeIT {
    @Autowired DiagnosticsSnapshot snapshot;
    @Autowired DataSource dataSource;

    private static Map<String, Object> section(Map<String, Object> s, String name) {
        return (Map<String, Object>) s.get(name);
    }

    @Test
    void allSectionsArePresent() {
        var s = snapshot.take();

        assertThat(s).containsKeys("taken_at", "truncated", "app", "jvm", "db_pool", "db", "outbox", "nats", "config", "disk", "lifecycle");
        assertThat(section(s, "app")).containsEntry("branch_id", "b01").containsKeys("version", "uptime_sec", "demo_mode");
        assertThat(section(s, "jvm")).containsKeys("threads_by_state", "blocked", "deadlocks", "heap_used_mb")
                .containsEntry("virtual_threads_listed", false);
        // the pool is its own section: read without the database, it survives a locked one
        assertThat(section(s, "db_pool")).containsKeys("active", "idle", "waiting", "max");
        assertThat(section(s, "db")).containsKeys("activity", "blocking", "largest_tables", "size_mb")
                .doesNotContainKey("error");
        assertThat(section(s, "outbox")).containsKeys("pending", "oldest_age_sec");
        assertThat(section(s, "nats")).containsKeys("connected", "state", "state_since", "leaf_linked", "streams");
        assertThat(section(s, "config")).containsKeys("central_version", "effective_version");
        assertThat(section(s, "disk")).containsKeys("free_mb", "total_mb");
    }

    @Test
    void lifecycleWithoutLogFileFailsAlone() {
        var s = snapshot.take();

        assertThat(section(s, "lifecycle")).containsKey("error");
        assertThat(section(s, "db")).doesNotContainKey("error");
    }

    @Test
    void deadlockIsReported() {
        var a = new Object();
        var b = new Object();
        var both = new CountDownLatch(2);
        startDaemon("deadlock-left", () -> { synchronized (a) { both.countDown(); awaitQuietly(both); synchronized (b) { } } });
        startDaemon("deadlock-right", () -> { synchronized (b) { both.countDown(); awaitQuietly(both); synchronized (a) { } } });

        await().untilAsserted(() -> assertThat((List<String>) section(snapshot.take(), "jvm").get("deadlocks"))
                .contains("deadlock-left", "deadlock-right"));
    }

    @Test
    void queryTextIsShortenedAndHasNoValues() throws Exception {
        var secret = "secret-value-" + "s".repeat(50);
        var longComment = "/* " + "c".repeat(300) + " */ ";
        try (var pool = Executors.newSingleThreadExecutor()) {
            var running = pool.submit(() -> jdbc.queryForObject(longComment + "SELECT pg_sleep(?)::text || ?", String.class, 3, secret));

            await().untilAsserted(() -> {
                var activity = (List<Map<String, Object>>) section(snapshot.take(), "db").get("activity");
                assertThat(activity).anySatisfy(q -> assertThat(q.get("query").toString()).startsWith("/* ccc"));
                assertThat(activity).allSatisfy(q -> {
                    assertThat(q.get("query").toString()).hasSizeLessThanOrEqualTo(200).doesNotContain("secret-value");
                });
            });
            running.get();
        }
    }

    /**
     * The case a snapshot is taken for: a lock holds requests, they hold every pooled connection. The {@code db}
     * section reads over its own connection, outside the pool, and still shows who blocks whom.
     */
    @Test
    void lockWithExhaustedPoolIsStillDiagnosed() throws Exception {
        var held = new ArrayList<Connection>();
        Connection locker = null;
        try {
            for (int i = 0; i < ((HikariDataSource) dataSource).getMaximumPoolSize(); i++) {
                held.add(dataSource.getConnection());
            }
            locker = held.get(0);
            locker.setAutoCommit(false);
            locker.createStatement().execute("LOCK TABLE tickets IN ACCESS EXCLUSIVE MODE");
            var blocked = held.get(1);
            startDaemon("blocked-by-lock", () -> {
                try (var st = blocked.createStatement()) {
                    st.executeQuery("SELECT count(*) FROM tickets");
                } catch (SQLException ignored) {
                    // released at the end of the test
                }
            });

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var s = snapshot.take();
                assertThat(section(s, "db")).doesNotContainKey("error");
                assertThat((List<Map<String, Object>>) section(s, "db").get("blocking"))
                        .anySatisfy(b -> assertThat(b.get("query").toString()).contains("FROM tickets"));
                assertThat(section(s, "db_pool")).containsEntry("idle", 0);
            });
        } finally {
            if (locker != null) {
                locker.rollback();                                   // first: the blocked query must finish
            }
            for (var c : held) {
                c.close();
            }
        }
    }

    private static void startDaemon(String name, Runnable body) {
        var t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

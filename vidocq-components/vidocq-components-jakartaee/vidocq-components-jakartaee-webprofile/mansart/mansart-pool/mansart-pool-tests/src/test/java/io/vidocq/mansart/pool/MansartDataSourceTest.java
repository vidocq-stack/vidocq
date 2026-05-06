package io.vidocq.mansart.pool;

import io.vidocq.mansart.pool.core.MansartDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MP-B — exercises the live borrow / release / housekeeping cycle against H2 in-memory.
 *
 * <p>Each test gets its own H2 database (random name) so concurrent runs do not collide.
 */
class MansartDataSourceTest {

    private MansartDataSource ds;

    @BeforeEach
    void setUp() {
        ds = MansartDataSource.of(PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .maxSize(8)
                .acquireTimeout(Duration.ofSeconds(2))
                .build());
    }

    @AfterEach
    void tearDown() {
        if (ds != null) ds.close();
    }

    @Test
    void borrowRunQueryRelease() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT 42");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(42);
        }
        // Connection returned to the pool: snapshot reflects 0 active, 1 idle, 1 borrow.
        PoolMetrics m = ds.snapshot();
        assertThat(m.active()).isZero();
        assertThat(m.idle()).isEqualTo(1);
        assertThat(m.totalBorrows()).isEqualTo(1L);
    }

    @Test
    void connectionsAreReusedFromIdle() throws SQLException {
        try (Connection c1 = ds.getConnection()) { /* borrow then release */ }
        try (Connection c2 = ds.getConnection()) {
            assertThat(c2.isClosed()).isFalse();
        }
        PoolMetrics m = ds.snapshot();
        assertThat(m.totalBorrows()).isEqualTo(2L);
        // Only one underlying connection should have been opened.
        assertThat(m.idle() + m.active()).isEqualTo(1);
    }

    @Test
    void borrowRespectsMaxSizeAndTimesOut() throws SQLException {
        Connection[] held = new Connection[8];
        for (int i = 0; i < 8; i++) held[i] = ds.getConnection();
        try {
            assertThatThrownBy(() -> ds.getConnection())
                    .isInstanceOf(PoolException.class)
                    .satisfies(t -> assertThat(((PoolException) t).reason())
                            .isEqualTo(PoolException.Reason.ACQUIRE_TIMEOUT));
        } finally {
            for (Connection c : held) c.close();
        }
        assertThat(ds.snapshot().totalTimeouts()).isEqualTo(1L);
    }

    @Test
    void closeOnPoolDisablesFurtherBorrows() throws SQLException {
        ds.close();
        assertThatThrownBy(() -> ds.getConnection())
                .isInstanceOf(PoolException.class)
                .satisfies(t -> assertThat(((PoolException) t).reason())
                        .isEqualTo(PoolException.Reason.POOL_CLOSED));
    }

    @Test
    void doubleCloseOnConnectionIsIdempotent() throws SQLException {
        Connection c = ds.getConnection();
        c.close();
        c.close();           // must NOT release the permit twice
        assertThat(c.isClosed()).isTrue();
        // Pool can still borrow up to maxSize fresh ones.
        Connection[] held = new Connection[8];
        for (int i = 0; i < 8; i++) held[i] = ds.getConnection();
        for (Connection h : held) h.close();
    }

    @Test
    void thousandConcurrentVirtualThreadBorrows() throws Exception {
        int n = 1_000;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?>[] futures = new Future<?>[n];
            for (int i = 0; i < n; i++) {
                futures[i] = es.submit(() -> {
                    start.await();
                    try (Connection c = ds.getConnection();
                         Statement s = c.createStatement();
                         var rs = s.executeQuery("SELECT 1")) {
                        if (rs.next() && rs.getInt(1) == 1) ok.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        }
        assertThat(ok.get()).isEqualTo(n);
        PoolMetrics m = ds.snapshot();
        assertThat(m.totalBorrows()).isEqualTo(n);
        // Pool must have stayed within maxSize: idle is bounded by maxSize.
        assertThat(m.idle()).isLessThanOrEqualTo(8);
    }
}

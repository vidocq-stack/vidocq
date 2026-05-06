package io.vidocq.mansart.pool.bench;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.mansart.pool.core.MansartDataSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Throughput under platform-thread contention. JMH's {@code @Threads(N)} drives N concurrent
 * platform threads; together with a {@code maxSize} smaller than N, it forces actual contention
 * on the borrow path so we measure the cost of the {@link java.util.concurrent.Semaphore}
 * coordination, not just a no-op poll on an empty deque.
 *
 * <p>Virtual-thread benchmarks have to run in a separate process — JMH does not natively
 * dispatch invocations on virtual threads. The platform-thread variant here is the apples-to-
 * apples comparator vs HikariCP, which is the configuration HikariCP was designed for.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Threads(8)
public class ConcurrentBorrowBench {

    @Param({"mansart", "hikari"})
    public String pool;

    private DataSource ds;

    @Setup
    public void setUp() {
        String jdbcUrl = "jdbc:h2:mem:bench-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        switch (pool) {
            case "mansart" -> ds = MansartDataSource.of(PoolConfig.builder()
                    .jdbcUrl(jdbcUrl).username("sa").maxSize(4).build());
            case "hikari"  -> {
                HikariConfig cfg = new HikariConfig();
                cfg.setJdbcUrl(jdbcUrl);
                cfg.setUsername("sa");
                cfg.setMaximumPoolSize(4);
                cfg.setConnectionTestQuery(null);
                ds = new HikariDataSource(cfg);
            }
            default -> throw new IllegalArgumentException(pool);
        }
    }

    @TearDown
    public void tearDown() {
        if (ds instanceof AutoCloseable c) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    @Benchmark
    public void borrowRelease() throws SQLException {
        Connection c = ds.getConnection();
        c.close();
    }
}

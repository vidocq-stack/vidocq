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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Borrow + immediate release on a single thread. Measures the cost of the pool's hot path with
 * no concurrency contention; useful as a baseline for the per-call overhead.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class BorrowReleaseBench {

    @Param({"mansart", "hikari"})
    public String pool;

    private DataSource ds;

    @Setup
    public void setUp() {
        String jdbcUrl = "jdbc:h2:mem:bench-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        switch (pool) {
            case "mansart" -> ds = MansartDataSource.of(PoolConfig.builder()
                    .jdbcUrl(jdbcUrl).username("sa").maxSize(16).build());
            case "hikari"  -> {
                HikariConfig cfg = new HikariConfig();
                cfg.setJdbcUrl(jdbcUrl);
                cfg.setUsername("sa");
                cfg.setMaximumPoolSize(16);
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

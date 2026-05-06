/**
 * Public API for Mansart Pool — config, metrics, exceptions. No runtime — see {@code mansart-pool-core}.
 */
module io.vidocq.mansart.pool.api {
    requires java.sql;          // javax.sql.DataSource is the value type referenced from PoolConfig

    exports io.vidocq.mansart.pool;
}

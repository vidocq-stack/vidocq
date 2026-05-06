package io.vidocq.mansart.pool;

/**
 * When the pool checks that an idle connection is still alive before handing it out.
 * Validation uses {@link java.sql.Connection#isValid(int)} (or {@link PoolConfig#validationQuery()}
 * when set).
 */
public enum ValidationMode {

    /** Never validate. Fastest, but a half-open TCP connection will surface as the next user's error. */
    NEVER,

    /** Validate every connection on borrow. Default when a {@code validationQuery} is set. */
    ON_BORROW,

    /**
     * Validate idle connections in the housekeeper, not on the borrow path.
     * Trades freshness for a fully lock-free borrow path on the hot loop.
     */
    PERIODIC
}

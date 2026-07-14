/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.pool;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration for a Mansart pool. Build via {@link #builder()}; every value is validated
 * at {@link Builder#build()} time, so an instance reaching {@code MansartDataSource} is by construction
 * coherent.
 *
 * <p>Defaults err on the side of safety, not speed:
 * <ul>
 *   <li>{@code minIdle = 0} — truly elastic pool, no idle warm-up.</li>
 *   <li>{@code maxSize = 10} — matches the historical HikariCP default and is a safe small-app value.</li>
 *   <li>{@code acquireTimeout = 5s} — fail fast under saturation.</li>
 *   <li>{@code idleTimeout = 10min} / {@code maxLifetime = 30min} — recycle eagerly, mirrors what
 *       most managed Postgres infrastructures (RDS, Cloud SQL) prefer.</li>
 *   <li>{@code validation = ON_BORROW} when {@link #validationQuery()} is non-null, else {@link
 *       ValidationMode#PERIODIC} — borrow-path defaults to lock-free.</li>
 * </ul>
 */
public record PoolConfig(
        String              jdbcUrl,
        String              username,
        String              password,
        int                 minIdle,
        int                 maxSize,
        Duration            acquireTimeout,
        Duration            idleTimeout,
        Duration            maxLifetime,
        Duration            validationTimeout,
        ValidationMode      validation,
        String              validationQuery,
        Duration            leakDetectionThreshold,
        Map<String, String> driverProperties,
        String              xaDataSourceClassName) {

    /** Sentinel meaning "leak detection disabled". Compared by reference, so do not duplicate. */
    public static final Duration LEAK_DETECTION_DISABLED = Duration.ZERO;

    public PoolConfig {
        // Canonical defensive copy — Builder already wraps as unmodifiable, but a caller could
        // bypass the builder by invoking the canonical constructor directly.
        driverProperties = driverProperties == null
                ? Map.of()
                : Map.copyOf(driverProperties);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String              jdbcUrl;
        private String              username;
        private String              password;
        private int                 minIdle                = 0;
        private int                 maxSize                = 10;
        private Duration            acquireTimeout         = Duration.ofSeconds(5);
        private Duration            idleTimeout            = Duration.ofMinutes(10);
        private Duration            maxLifetime            = Duration.ofMinutes(30);
        private Duration            validationTimeout      = Duration.ofSeconds(1);
        private ValidationMode      validation;            // resolved at build()
        private String              validationQuery;       // null → use Connection.isValid
        private Duration            leakDetectionThreshold = LEAK_DETECTION_DISABLED;
        private final Map<String, String> driverProperties = new LinkedHashMap<>();
        private String              xaDataSourceClassName;

        private Builder() {}

        public Builder jdbcUrl(String v)              { this.jdbcUrl = v;               return this; }
        public Builder username(String v)             { this.username = v;              return this; }
        public Builder password(String v)             { this.password = v;              return this; }
        public Builder minIdle(int v)                 { this.minIdle = v;               return this; }
        public Builder maxSize(int v)                 { this.maxSize = v;               return this; }
        public Builder acquireTimeout(Duration v)     { this.acquireTimeout = v;        return this; }
        public Builder idleTimeout(Duration v)        { this.idleTimeout = v;           return this; }
        public Builder maxLifetime(Duration v)        { this.maxLifetime = v;           return this; }
        public Builder validationTimeout(Duration v)  { this.validationTimeout = v;     return this; }
        public Builder validation(ValidationMode v)   { this.validation = v;            return this; }
        public Builder validationQuery(String v)      { this.validationQuery = v;       return this; }
        public Builder leakDetectionThreshold(Duration v) { this.leakDetectionThreshold = v; return this; }

        /**
         * Optional (MANSART-007 phase 2) — fully qualified name of the driver's
         * {@link javax.sql.XADataSource} implementation (e.g. {@code org.h2.jdbcx.JdbcDataSource},
         * {@code org.postgresql.xa.PGXADataSource}). When set, the pool exposes that XADataSource
         * through {@code unwrap(XADataSource.class)} so a JTA bridge can enlist the driver's
         * XAResource (real two-phase commit). Pooled connections themselves are unaffected;
         * transactional XA connections are opened outside the pool, one per transaction.
         */
        public Builder xaDataSourceClassName(String v) { this.xaDataSourceClassName = v; return this; }

        public Builder driverProperty(String k, String v) {
            driverProperties.put(Objects.requireNonNull(k, "key"),
                                 Objects.requireNonNull(v, "value"));
            return this;
        }

        public PoolConfig build() {
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("jdbcUrl must be set");
            }
            if (minIdle < 0)                         throw new IllegalArgumentException("minIdle must be >= 0, got " + minIdle);
            if (maxSize < 1)                         throw new IllegalArgumentException("maxSize must be >= 1, got " + maxSize);
            if (minIdle > maxSize)                   throw new IllegalArgumentException("minIdle (" + minIdle + ") must be <= maxSize (" + maxSize + ")");
            requirePositive("acquireTimeout",      acquireTimeout);
            requirePositive("idleTimeout",         idleTimeout);
            requirePositive("maxLifetime",         maxLifetime);
            requirePositive("validationTimeout",   validationTimeout);
            requireNonNegative("leakDetectionThreshold", leakDetectionThreshold);
            if (idleTimeout.compareTo(maxLifetime) > 0) {
                throw new IllegalArgumentException(
                        "idleTimeout (" + idleTimeout + ") must be <= maxLifetime (" + maxLifetime + ")");
            }

            ValidationMode resolvedValidation = validation != null
                    ? validation
                    : (validationQuery != null ? ValidationMode.ON_BORROW : ValidationMode.PERIODIC);

            return new PoolConfig(
                    jdbcUrl, username, password,
                    minIdle, maxSize,
                    acquireTimeout, idleTimeout, maxLifetime, validationTimeout,
                    resolvedValidation, validationQuery, leakDetectionThreshold,
                    Map.copyOf(driverProperties), xaDataSourceClassName);
        }

        private static void requirePositive(String name, Duration d) {
            if (d == null || d.isZero() || d.isNegative()) {
                throw new IllegalArgumentException(name + " must be a strictly positive Duration, got " + d);
            }
        }

        private static void requireNonNegative(String name, Duration d) {
            if (d == null || d.isNegative()) {
                throw new IllegalArgumentException(name + " must be a non-negative Duration, got " + d);
            }
        }
    }
}

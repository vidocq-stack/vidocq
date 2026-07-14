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

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MP-A — exercises {@link PoolConfig.Builder} validation and defaults. The pool itself is not
 * involved; we only assert that an instance reaching the runtime is by construction coherent.
 */
class PoolConfigBuilderTest {

    @Test
    void minimalBuildSetsSafeDefaults() {
        PoolConfig c = PoolConfig.builder().jdbcUrl("jdbc:h2:mem:test").build();

        assertThat(c.minIdle()).isZero();
        assertThat(c.maxSize()).isEqualTo(10);
        assertThat(c.acquireTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(c.idleTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(c.maxLifetime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(c.validationTimeout()).isEqualTo(Duration.ofSeconds(1));
        // No validationQuery → PERIODIC keeps the borrow path lock-free.
        assertThat(c.validation()).isEqualTo(ValidationMode.PERIODIC);
        assertThat(c.validationQuery()).isNull();
        assertThat(c.leakDetectionThreshold()).isEqualTo(PoolConfig.LEAK_DETECTION_DISABLED);
        assertThat(c.driverProperties()).isEmpty();
    }

    @Test
    void validationDefaultsToOnBorrowWhenQuerySet() {
        PoolConfig c = PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:test")
                .validationQuery("SELECT 1")
                .build();
        assertThat(c.validation()).isEqualTo(ValidationMode.ON_BORROW);
    }

    @Test
    void explicitValidationModeWinsOverDefault() {
        PoolConfig c = PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:test")
                .validationQuery("SELECT 1")
                .validation(ValidationMode.NEVER)
                .build();
        assertThat(c.validation()).isEqualTo(ValidationMode.NEVER);
    }

    @Test
    void driverPropertiesAreCopiedAndImmutable() {
        PoolConfig.Builder b = PoolConfig.builder().jdbcUrl("jdbc:h2:mem:test")
                .driverProperty("ssl", "true")
                .driverProperty("ApplicationName", "vidocq");

        PoolConfig c = b.build();
        assertThat(c.driverProperties())
                .containsEntry("ssl", "true")
                .containsEntry("ApplicationName", "vidocq")
                .hasSize(2);

        // Caller mutating the builder after build() must not affect the config.
        b.driverProperty("late", "intruder");
        assertThat(c.driverProperties()).doesNotContainKey("late");

        // Returned map is unmodifiable.
        assertThatThrownBy(() -> c.driverProperties().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void canonicalConstructorDefensivelyCopiesNullProperties() {
        PoolConfig c = new PoolConfig(
                "jdbc:h2:mem:test", null, null,
                0, 5,
                Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofMinutes(2),
                Duration.ofMillis(500), ValidationMode.NEVER, null,
                Duration.ZERO, null, null);
        assertThat(c.driverProperties()).isEmpty();
    }

    @Test
    void rejectsMissingJdbcUrl() {
        assertThatThrownBy(() -> PoolConfig.builder().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbcUrl");
        assertThatThrownBy(() -> PoolConfig.builder().jdbcUrl("   ").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeMinIdle() {
        assertThatThrownBy(() -> PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:test").minIdle(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minIdle");
    }

    @Test
    void rejectsZeroMaxSize() {
        assertThatThrownBy(() -> PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:test").maxSize(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSize");
    }

    @Test
    void rejectsMinIdleAboveMaxSize() {
        assertThatThrownBy(() -> PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:test").minIdle(20).maxSize(5).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("minIdle", "maxSize");
    }

    @Test
    void rejectsZeroOrNegativeTimeouts() {
        assertThatThrownBy(() -> PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:test").acquireTimeout(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acquireTimeout");
        assertThatThrownBy(() -> PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:test").idleTimeout(Duration.ofSeconds(-1)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idleTimeout");
        assertThatThrownBy(() -> PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:test").maxLifetime(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLifetime");
        assertThatThrownBy(() -> PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:test").validationTimeout(Duration.ofSeconds(-1)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validationTimeout");
    }

    @Test
    void leakDetectionThresholdAcceptsZeroAsDisabled() {
        PoolConfig c = PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:test")
                .leakDetectionThreshold(PoolConfig.LEAK_DETECTION_DISABLED)
                .build();
        assertThat(c.leakDetectionThreshold()).isEqualTo(Duration.ZERO);

        assertThatThrownBy(() -> PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:test")
                .leakDetectionThreshold(Duration.ofSeconds(-1))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leakDetectionThreshold");
    }

    @Test
    void rejectsIdleTimeoutAboveMaxLifetime() {
        assertThatThrownBy(() -> PoolConfig.builder()
                .jdbcUrl("jdbc:h2:mem:test")
                .idleTimeout(Duration.ofMinutes(30))
                .maxLifetime(Duration.ofMinutes(10))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("idleTimeout", "maxLifetime");
    }
}

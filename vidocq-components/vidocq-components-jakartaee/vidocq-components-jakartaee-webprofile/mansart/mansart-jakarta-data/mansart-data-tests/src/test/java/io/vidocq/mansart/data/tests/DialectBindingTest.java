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
package io.vidocq.mansart.data.tests;

import io.vidocq.mansart.data.dialect.Dialect;
import io.vidocq.mansart.data.dialect.h2.H2Dialect;
import io.vidocq.mansart.data.dialect.postgresql.PostgresqlDialect;
import jakarta.data.exceptions.EntityExistsException;
import jakarta.data.exceptions.MappingException;
import jakarta.data.exceptions.OptimisticLockingFailureException;
import io.vidocq.mansart.data.core.MansartDataException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The H2 and PostgreSQL dialects bind a value of each supported Java type to a statement parameter and read it back
 * from a result column, {@code null} included, on H2 (in PostgreSQL mode for the PostgreSQL dialect).
 */
class DialectBindingTest {

    private static Connection connect(Dialect dialect) throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(dialect instanceof PostgresqlDialect
                ? "jdbc:h2:mem:mansart-binding-pg;MODE=PostgreSQL" : "jdbc:h2:mem:mansart-binding");
        ds.setUser("sa");
        return ds.getConnection();
    }

    static Stream<Arguments> values() {
        Stream<Object[]> cases = Stream.of(
                new Object[]{"text", String.class, "VARCHAR(20)"},
                new Object[]{true, Boolean.class, "BOOLEAN"},
                new Object[]{(byte) 7, Byte.class, "SMALLINT"},
                new Object[]{(short) 7, Short.class, "SMALLINT"},
                new Object[]{7, Integer.class, "INTEGER"},
                new Object[]{7L, Long.class, "BIGINT"},
                new Object[]{1.5f, Float.class, "REAL"},
                new Object[]{2.5d, Double.class, "DOUBLE PRECISION"},
                new Object[]{new BigDecimal("12.34"), BigDecimal.class, "NUMERIC(10, 2)"},
                new Object[]{LocalDate.of(2026, 10, 28), LocalDate.class, "DATE"},
                new Object[]{LocalTime.of(12, 30, 15), LocalTime.class, "TIME"},
                new Object[]{LocalDateTime.of(2026, 10, 28, 12, 30), LocalDateTime.class, "TIMESTAMP"},
                new Object[]{OffsetDateTime.of(2026, 10, 28, 12, 30, 0, 0, ZoneOffset.UTC), OffsetDateTime.class,
                        "TIMESTAMP WITH TIME ZONE"},
                new Object[]{Instant.parse("2026-10-28T12:30:00Z"), Instant.class, "TIMESTAMP WITH TIME ZONE"},
                new Object[]{UUID.fromString("4d414e53-0000-0000-0000-000000000001"), UUID.class, "UUID"},
                new Object[]{Priority.values()[0], Priority.class, "VARCHAR(20)"},
                new Object[]{'x', Character.class, "VARCHAR(1)"});
        return cases.flatMap(c -> Stream.of(new H2Dialect(), new PostgresqlDialect())
                .flatMap(d -> Stream.of(Arguments.of(d.name(), d, c[0], c[1], c[2]),
                        Arguments.of(d.name(), d, null, c[1], c[2]))));
    }

    @ParameterizedTest(name = "{0} {3} {2}")
    @MethodSource("values")
    void bindsAndExtractsEachType(String name, Dialect dialect, Object value, Class<?> javaType, String sqlType)
            throws SQLException {
        try (Connection c = connect(dialect);
             PreparedStatement ps = c.prepareStatement("SELECT CAST(? AS " + sqlType + ")")) {
            dialect.bind(ps, 1, value, javaType);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(dialect.extract(rs, 1, javaType)).as(name).isEqualTo(value);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void mapsJavaTypesToSqlTypes(String name, Dialect dialect) {
        assertThat(dialect.sqlType(String.class)).isEqualTo(Types.VARCHAR);
        assertThat(dialect.sqlType(Instant.class)).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(dialect.sqlType(Priority.class)).isEqualTo(Types.VARCHAR);
        assertThat(dialect.sqlType(UUID.class)).isEqualTo(Types.OTHER);
        assertThat(dialect.sqlType(Object.class)).as(name).isEqualTo(Types.OTHER);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void translatesSqlStates(String name, Dialect dialect) {
        assertThat(dialect.translate(new SQLException("dup", "23505"))).isInstanceOf(EntityExistsException.class);
        assertThat(dialect.translate(new SQLException("ser", "40001")))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(dialect.translate(new SQLException("fk", "23503"))).isInstanceOf(MappingException.class);
        assertThat(dialect.translate(new SQLException("other", "42000"))).isInstanceOf(MansartDataException.class);
        assertThat(dialect.translate(new SQLException("none"))).as(name).isInstanceOf(MansartDataException.class);
    }

    static Stream<Arguments> dialects() {
        return Stream.of(new H2Dialect(), new PostgresqlDialect()).map(d -> Arguments.of(d.name(), d));
    }

    @Test
    void postgresqlUpsertsOnTheIdConflict() {
        assertThat(new PostgresqlDialect().merge(_Author.$MODEL).sql()).isEqualTo(
                "INSERT INTO \"authors\" (\"id\", \"name\") VALUES (?, ?) "
                        + "ON CONFLICT (\"id\") DO UPDATE SET \"name\" = EXCLUDED.\"name\"");
    }
}

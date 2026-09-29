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

import io.vidocq.mansart.data.core.EntityModels;
import io.vidocq.mansart.data.core.JdqlExecutor;
import io.vidocq.mansart.data.core.JdqlResult;
import io.vidocq.mansart.data.core.MansartData;
import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import io.vidocq.mansart.data.dialect.EntityModel;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JdqlExecutor#run}: a JDQL statement given as text, as a tool such as the Vidocq dev console runs it, on H2 —
 * the shapes of its result, the conversion of its values to the attributes' types, and what it refuses.
 */
class JdqlRunTest {

    private static DataSource dataSource;
    private static RepositoryRuntime runtime;
    private static EntityModel<Shipment> model;

    @BeforeAll
    static void initStack() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mansart-jdql-run;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        dataSource = ds;
        runtime = MansartData.builder().dataSource(ds).build().runtime();
        model = EntityModels.of(Shipment.class);
    }

    @BeforeEach
    void resetRows() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS \"jdql_shipments\"");
            s.execute("CREATE TABLE \"jdql_shipments\" (\"id\" BIGINT PRIMARY KEY, \"reference\" VARCHAR(20),"
                    + " \"status\" VARCHAR(20), \"shipped_on\" DATE, \"logged_at\" TIMESTAMP WITH TIME ZONE,"
                    + " \"weight\" NUMERIC(10, 2), \"parcels\" INTEGER, \"fragile\" BOOLEAN)");
            s.execute("INSERT INTO \"jdql_shipments\" VALUES"
                    + " (1, 'A-1', 'PENDING', DATE '2026-09-01',"
                    + " TIMESTAMP WITH TIME ZONE '2026-09-01 08:00:00+00', 2.50, 1, TRUE),"
                    + " (2, 'B-2', 'SHIPPED', DATE '2026-09-10',"
                    + " TIMESTAMP WITH TIME ZONE '2026-09-10 09:30:00+00', 12.75, 3, FALSE),"
                    + " (3, 'C-3', 'SHIPPED', DATE '2026-09-20',"
                    + " TIMESTAMP WITH TIME ZONE '2026-09-20 17:45:00+00', 7.00, 2, FALSE),"
                    + " (4, 'D-4', 'DELIVERED', DATE '2026-08-15',"
                    + " TIMESTAMP WITH TIME ZONE '2026-08-15 12:00:00+00', 0.80, 1, TRUE)");
        }
    }

    private static JdqlResult run(String jdql, Map<String, ?> parameters) {
        return JdqlExecutor.run(jdql, parameters, model, runtime);
    }

    /** The references of the entities {@code result} holds, in its order. */
    private static List<String> references(JdqlResult result) {
        assertThat(result).isInstanceOf(JdqlResult.Entities.class);
        return ((JdqlResult.Entities) result).entities().stream().map(e -> ((Shipment) e).getReference()).toList();
    }

    /** The rows of a projection, each as a list. */
    private static List<List<Object>> values(JdqlResult result) {
        assertThat(result).isInstanceOf(JdqlResult.Rows.class);
        return ((JdqlResult.Rows) result).rows().stream().map(row -> Arrays.asList(row)).toList();
    }

    private static long count(String jdql, Map<String, ?> parameters) {
        return ((JdqlResult.Count) run(jdql, parameters)).count();
    }

    /** Parameters that may hold {@code null}, which {@link Map#of} refuses. */
    private static Map<String, Object> params(String name, Object value) {
        Map<String, Object> out = new HashMap<>();
        out.put(name, value);
        return out;
    }

    @Test
    void aPlainQueryReturnsTheEntitiesInTheirOrder() {
        JdqlResult result = run("FROM Shipment ORDER BY id", Map.of());

        assertThat(references(result)).containsExactly("A-1", "B-2", "C-3", "D-4");
        Shipment first = (Shipment) ((JdqlResult.Entities) result).entities().getFirst();
        assertThat(first.getStatus()).isEqualTo(ShipmentStatus.PENDING);
        assertThat(first.getShippedOn()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void aNamedParameterGivenAsTextIsConvertedToAnEnum() {
        assertThat(references(run("FROM Shipment WHERE status = :status ORDER BY id", Map.of("status", "SHIPPED"))))
                .containsExactly("B-2", "C-3");
    }

    @Test
    void aValueOfTheAttributesTypeIsKept() {
        assertThat(references(run("FROM Shipment WHERE status = :status", Map.of("status", ShipmentStatus.PENDING))))
                .containsExactly("A-1");
    }

    @Test
    void aLiteralIsConvertedToo() {
        assertThat(references(run("FROM Shipment WHERE status = 'DELIVERED'", Map.of()))).containsExactly("D-4");
        assertThat(references(run("FROM Shipment WHERE status = DELIVERED", Map.of()))).containsExactly("D-4");
        assertThat(references(run("FROM Shipment WHERE shippedOn >= '2026-09-10' ORDER BY id", Map.of())))
                .containsExactly("B-2", "C-3");
        assertThat(references(run("FROM Shipment WHERE weight > '10'", Map.of()))).containsExactly("B-2");
    }

    @Test
    void datesAndInstantsAreReadAsIsoText() {
        assertThat(references(run("FROM Shipment WHERE loggedAt < :before ORDER BY id",
                Map.of("before", "2026-09-05T00:00:00Z")))).containsExactly("A-1", "D-4");
        assertThat(references(run("FROM Shipment WHERE shippedOn BETWEEN :from AND :to ORDER BY id",
                Map.of("from", "2026-09-01", "to", "2026-09-10")))).containsExactly("A-1", "B-2");
    }

    @Test
    void numbersAreConvertedExactly() {
        assertThat(references(run("FROM Shipment WHERE parcels = :n", Map.of("n", "3")))).containsExactly("B-2");
        assertThat(references(run("FROM Shipment WHERE parcels = :n", Map.of("n", new BigDecimal("3")))))
                .containsExactly("B-2");
        assertThat(references(run("FROM Shipment WHERE weight > :w ORDER BY id", Map.of("w", 7))))
                .containsExactly("B-2");
        assertThat(references(run("FROM Shipment WHERE fragile = :f ORDER BY id", Map.of("f", "true"))))
                .containsExactly("A-1", "D-4");
    }

    @Test
    void aListParameterFeedsAnInElementByElement() {
        assertThat(references(run("FROM Shipment WHERE status IN :states ORDER BY id",
                Map.of("states", List.of("PENDING", "DELIVERED"))))).containsExactly("A-1", "D-4");
        assertThat(references(run("FROM Shipment WHERE status IN :states", Map.of("states", List.of()))))
                .isEmpty();
    }

    @Test
    void aValueThatDoesNotConvertIsRefusedNamingIt() {
        assertThatThrownBy(() -> run("FROM Shipment WHERE status = :status", Map.of("status", "LOST")))
                .isInstanceOf(MansartDataException.class).hasMessage(":status: not a ShipmentStatus");
        assertThatThrownBy(() -> run("FROM Shipment WHERE parcels = :n", Map.of("n", "2.5")))
                .isInstanceOf(MansartDataException.class).hasMessage(":n: not a Integer");
        assertThatThrownBy(() -> run("FROM Shipment WHERE parcels = :n", Map.of("n", new BigDecimal("2.5"))))
                .hasMessage(":n: not a Integer");
        assertThatThrownBy(() -> run("FROM Shipment WHERE shippedOn = :d", Map.of("d", "yesterday")))
                .hasMessage(":d: not a LocalDate");
        assertThatThrownBy(() -> run("FROM Shipment WHERE loggedAt < :at", Map.of("at", "2026-09-01")))
                .hasMessage(":at: not a Instant");
        assertThatThrownBy(() -> run("FROM Shipment WHERE fragile = :f", Map.of("f", "yes")))
                .hasMessage(":f: not a Boolean");
        assertThatThrownBy(() -> run("FROM Shipment WHERE status = 'LOST'", Map.of()))
                .isInstanceOf(MansartDataException.class).hasMessage("'LOST': not a ShipmentStatus");
    }

    @Test
    void aWriteWhoseValueDoesNotConvertChangesNothing() {
        assertThatThrownBy(() -> run("UPDATE Shipment SET status = :to WHERE status = :from",
                Map.of("to", "LOST", "from", "SHIPPED"))).hasMessage(":to: not a ShipmentStatus");

        assertThat(count("SELECT COUNT(this) FROM Shipment WHERE status = :s", Map.of("s", "SHIPPED"))).isEqualTo(2);
    }

    @Test
    void aProjectionOfOneColumnIsRowsOfOneValue() {
        JdqlResult result = run("SELECT reference FROM Shipment WHERE fragile = :f ORDER BY reference",
                Map.of("f", true));

        assertThat(((JdqlResult.Rows) result).columns()).containsExactly("reference");
        assertThat(values(result)).containsExactly(List.of("A-1"), List.of("D-4"));
    }

    @Test
    void aProjectionOfSeveralColumnsKeepsTheirOrder() {
        JdqlResult result = run("SELECT reference, parcels FROM Shipment WHERE status = :s ORDER BY reference",
                Map.of("s", "SHIPPED"));

        assertThat(((JdqlResult.Rows) result).columns()).containsExactly("reference", "parcels");
        assertThat(values(result)).containsExactly(List.of("B-2", 3), List.of("C-3", 2));
    }

    @Test
    void aCountCountsTheRows() {
        assertThat(run("SELECT COUNT(this) FROM Shipment WHERE status = :s", Map.of("s", "SHIPPED")))
                .isEqualTo(new JdqlResult.Count(2));
    }

    @Test
    void anAggregateIsItsValueOrNullWithoutRows() {
        JdqlResult.Value max = (JdqlResult.Value) run("SELECT MAX(weight) FROM Shipment", Map.of());
        assertThat((BigDecimal) max.value()).isEqualByComparingTo("12.75");
        assertThat(run("SELECT MAX(parcels) FROM Shipment WHERE reference = :r", Map.of("r", "none")))
                .isEqualTo(new JdqlResult.Value(null));
        assertThat(run("SELECT SUM(parcels) FROM Shipment", Map.of())).isEqualTo(new JdqlResult.Value(7L));
    }

    @Test
    void anUpdateCountsTheRowsItChangedAndChangesThem() {
        assertThat(run("UPDATE Shipment SET status = :to WHERE status = :from",
                Map.of("to", "DELIVERED", "from", "SHIPPED"))).isEqualTo(new JdqlResult.Count(2));
        assertThat(count("SELECT COUNT(this) FROM Shipment WHERE status = :s", Map.of("s", "DELIVERED")))
                .isEqualTo(3);

        assertThat(run("UPDATE Shipment SET parcels = parcels + :more WHERE reference = :r",
                Map.of("more", "2", "r", "A-1"))).isEqualTo(new JdqlResult.Count(1));
        assertThat(values(run("SELECT parcels FROM Shipment WHERE reference = 'A-1'", Map.of())))
                .containsExactly(List.of(3));
    }

    @Test
    void aDeleteCountsTheRowsItRemovedAndRemovesThem() {
        assertThat(run("DELETE FROM Shipment WHERE fragile = true", Map.of())).isEqualTo(new JdqlResult.Count(2));
        assertThat(references(run("FROM Shipment ORDER BY id", Map.of()))).containsExactly("B-2", "C-3");
    }

    @Test
    void aParameterWithoutValueOrAValueWithoutParameterIsRefused() {
        assertThatThrownBy(() -> run("FROM Shipment WHERE status = :status", Map.of()))
                .isInstanceOf(MansartDataException.class).hasMessage(":status: no value given");
        assertThatThrownBy(() -> run("FROM Shipment WHERE status = :status",
                Map.of("status", "PENDING", "extra", 1)))
                .isInstanceOf(MansartDataException.class).hasMessage(":extra: not used by the statement");
        assertThat(references(run("FROM Shipment WHERE reference = :r", params("r", null)))).isEmpty();
    }

    @Test
    void positionalParametersAreRefused() {
        assertThatThrownBy(() -> run("FROM Shipment WHERE parcels = ?1", Map.of()))
                .isInstanceOf(MansartDataException.class)
                .hasMessage("positional parameters are not supported here; use :name");
    }

    @Test
    void theEntityMayBeNamedByItsFullClassName() {
        assertThat(references(run("FROM io.vidocq.mansart.data.tests.Shipment WHERE id = :id", Map.of("id", 4))))
                .containsExactly("D-4");
    }

    @Test
    void aStatementThatDoesNotParseIsAMansartDataException() {
        assertThatThrownBy(() -> run("FROM Shipment WHERE", Map.of())).isInstanceOf(MansartDataException.class);
        assertThatThrownBy(() -> run("FROM Parcel", Map.of())).isInstanceOf(MansartDataException.class)
                .hasMessageContaining("Parcel");
    }

    @Test
    void isWriteReadsTheFirstKeyword() {
        assertThat(JdqlExecutor.isWrite("  update Shipment SET parcels = 1")).isTrue();
        assertThat(JdqlExecutor.isWrite("DELETE FROM Shipment")).isTrue();
        assertThat(JdqlExecutor.isWrite("FROM Shipment")).isFalse();
        assertThat(JdqlExecutor.isWrite("SELECT COUNT(this) FROM Shipment")).isFalse();
        assertThat(JdqlExecutor.isWrite("UPDATED")).isFalse();
        assertThat(JdqlExecutor.isWrite("")).isFalse();
        assertThat(JdqlExecutor.isWrite(null)).isFalse();
    }

    @Test
    void targetIsTheEntityAStatementNames() {
        assertThat(JdqlExecutor.target("FROM Shipment WHERE parcels > 1")).contains("Shipment");
        assertThat(JdqlExecutor.target("select reference, parcels from Shipment")).contains("Shipment");
        assertThat(JdqlExecutor.target("SELECT COUNT(this) FROM Shipment")).contains("Shipment");
        assertThat(JdqlExecutor.target("UPDATE Shipment SET parcels = 1")).contains("Shipment");
        assertThat(JdqlExecutor.target("DELETE FROM Shipment WHERE parcels = 0")).contains("Shipment");
        assertThat(JdqlExecutor.target("FROM io.vidocq.mansart.data.tests.Shipment"))
                .contains("io.vidocq.mansart.data.tests.Shipment");
        assertThat(JdqlExecutor.target("WHERE parcels > 1")).isEmpty();
        assertThat(JdqlExecutor.target("UPDATE SET parcels = 1")).isEmpty();
        assertThat(JdqlExecutor.target("DELETE WHERE parcels = 0")).isEmpty();
        assertThat(JdqlExecutor.target(null)).isEmpty();
    }

    @Test
    void targetSkipsStringLiteralsAndParameters() {
        assertThat(JdqlExecutor.target("UPDATE Shipment SET reference = 'FROM Elsewhere'")).contains("Shipment");
        assertThat(JdqlExecutor.target(
                "SELECT reference FROM Shipment WHERE reference = 'from x' OR reference = :from"))
                .contains("Shipment");
        assertThat(JdqlExecutor.target("SELECT reference WHERE reference = 'FROM Elsewhere'")).isEmpty();
    }

    @Test
    void aSelectClauseThenAWriteIsRefusedAndChangesNothing() {
        long before = count("SELECT COUNT(this) FROM Shipment", Map.of());

        assertThatThrownBy(() -> run("SELECT this DELETE FROM Shipment", Map.of()))
                .isInstanceOf(MansartDataException.class);
        assertThatThrownBy(() -> run("SELECT COUNT(this) UPDATE Shipment SET parcels = 0", Map.of()))
                .isInstanceOf(MansartDataException.class);

        assertThat(count("SELECT COUNT(this) FROM Shipment", Map.of())).isEqualTo(before);
        assertThat(JdqlExecutor.isWrite("SELECT this DELETE FROM Shipment")).isFalse();
    }
}

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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Broken;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Part;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Slot;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Task;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code jdql} language of the Mansart Data panel (query mode spec §2.4): the dialect of JDQL, one target per
 * entity whose model could be read, its attributes typed as the page's editor checks them.
 */
class JdqlLanguageTest {

    private static MansartDataCatalogue.Entity entity(String name, Class<?> type, String table) {
        return new MansartDataCatalogue.Entity(name, type.getName(), table, List.of(), null);
    }

    /** The language of these entities, their classes loaded as the panel loads them, their models the fixtures'. */
    private static Map<?, ?> language(MansartDataCatalogue.Entity... entities) {
        return (Map<?, ?>) Json.parse(JdqlLanguage.json(List.of(entities),
                className -> RepositoryActions.load(className, RunFixtures.REPOSITORIES), RunFixtures::model));
    }

    private static Map<?, ?> targets(MansartDataCatalogue.Entity... entities) {
        return (Map<?, ?>) language(entities).get("targets");
    }

    @Test
    void theDialectIsTheWordsOfMansartsJdql() {
        assertEquals("{\"mode\":\"query\",\"dialect\":{\"keywords\":[\"SELECT\",\"FROM\",\"WHERE\",\"ORDER\",\"BY\","
                + "\"AND\",\"OR\",\"NOT\",\"IS\",\"NULL\",\"BETWEEN\",\"LIKE\",\"IN\",\"ASC\",\"DESC\",\"UPDATE\","
                + "\"SET\",\"DELETE\",\"COUNT\",\"THIS\",\"SUM\",\"AVG\",\"MIN\",\"MAX\",\"TRUE\",\"FALSE\"],"
                + "\"functions\":[\"UPPER\",\"LOWER\",\"LENGTH\",\"ABS\",\"CONCAT\",\"COUNT\",\"SUM\",\"AVG\",\"MIN\","
                + "\"MAX\"],\"clauses\":[\"SELECT\",\"FROM\",\"WHERE\",\"ORDER BY\",\"SET\",\"UPDATE\",\"DELETE FROM\"],"
                + "\"targetAfter\":[\"FROM\",\"UPDATE\"],\"self\":\"this\",\"quote\":\"'\"},\"targets\":{}}",
                JdqlLanguage.json(List.of(), className -> null, type -> null));
    }

    @Test
    void oneTargetPerEntityWhoseModelIsReadUnderItsSimpleNameInNameOrder() {
        Map<?, ?> targets = targets(entity("Task", Task.class, "tasks"), entity("Gizmo", Gizmo.class, "gizmos"),
                new MansartDataCatalogue.Entity("Broken", Broken.class.getName(), "", List.of(), "unusable model"),
                new MansartDataCatalogue.Entity("Missing", "com.acme.Missing", "missing", List.of(), null),
                new MansartDataCatalogue.Entity("x.Twin", "x.Twin", "twins", List.of(), null),
                new MansartDataCatalogue.Entity("y.Twin", "y.Twin", "twins", List.of(), null),
                entity("Part", Part.class, "parts"));

        assertEquals(List.of("Gizmo", "Part", "Task"), List.copyOf(targets.keySet()),
                "no model, no class, or a name two entities share: left out");
        assertEquals("table gizmos", ((Map<?, ?>) targets.get("Gizmo")).get("detail"));
    }

    @Test
    void anAttributeHasTheTypeTheEditorChecksAndADetailOfItsJavaTypeAndItsKeyOrColumn() {
        assertEquals("{\"id\":{\"type\":\"integer\",\"detail\":\"Long · id, generated\"},"
                + "\"version\":{\"type\":\"integer\",\"detail\":\"Long · version\"},"
                + "\"title\":{\"type\":\"string\",\"detail\":\"String · column title\"},"
                + "\"notes\":{\"type\":\"string\",\"detail\":\"String · column notes\"},"
                + "\"points\":{\"type\":\"integer\",\"detail\":\"Integer · column points\"},"
                + "\"level\":{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"],\"detail\":\"Level · column level\"},"
                + "\"owner\":{\"type\":\"integer\",\"detail\":\"→ Gizmo · column owner_id\",\"target\":\"Gizmo\"}}",
                Json.write(((Map<?, ?>) targets(entity("Task", Task.class, "tasks")).get("Task")).get("attributes")));
        assertEquals("{\"id\":{\"type\":\"integer\",\"detail\":\"Long · id\"},"
                + "\"name\":{\"type\":\"string\",\"detail\":\"String · column name\"},"
                + "\"stock\":{\"type\":\"integer\",\"detail\":\"Integer · column stock\"},"
                + "\"level\":{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"],\"detail\":\"Level · column level\"},"
                + "\"due\":{\"type\":\"string\",\"format\":\"date\",\"detail\":\"LocalDate · column due\"},"
                + "\"price\":{\"type\":\"number\",\"detail\":\"BigDecimal · column price\"}}",
                Json.write(((Map<?, ?>) targets(entity("Gizmo", Gizmo.class, "gizmos")).get("Gizmo")).get("attributes")),
                "a model Mansart builds itself");
    }

    @Test
    void aReferenceNamesItsTargetAndAJoinedAttributeIsLeftOut() {
        assertEquals("{\"id\":{\"type\":\"integer\",\"detail\":\"Long · id, generated\"},"
                + "\"gizmo\":{\"type\":\"integer\",\"detail\":\"→ Gizmo · column gizmo_id\",\"target\":\"Gizmo\"},"
                + "\"label\":{\"type\":\"string\",\"detail\":\"String · column label\"}}",
                Json.write(((Map<?, ?>) targets(entity("Part", Part.class, "parts")).get("Part")).get("attributes")),
                "the gizmo's name, joined, is no attribute of the language");
    }

    @Test
    void anAttributeOfATypeWithNoJsonSchemaIsKeptWithoutAType() {
        assertEquals("{\"detail\":\"Year · column year\"}", Json.write(((Map<?, ?>) ((Map<?, ?>) targets(
                entity("Slot", Slot.class, "slots")).get("Slot")).get("attributes")).get("year")));
    }
}

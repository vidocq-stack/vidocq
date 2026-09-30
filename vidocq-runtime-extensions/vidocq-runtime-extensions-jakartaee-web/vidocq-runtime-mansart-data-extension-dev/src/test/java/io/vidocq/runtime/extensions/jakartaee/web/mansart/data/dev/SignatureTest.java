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

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.BrokenRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.GizmoRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.TaskRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The methods of a repository, and for each its schema, why it cannot run, and its arguments (spec §2-§4). */
class SignatureTest {

    private final EntityJson entities = RunFixtures.entities();

    private static RepositoryMethods.Candidate candidate(Class<?> repository, String signature) {
        return RepositoryMethods.of(repository).stream().filter(c -> c.signature().equals(signature)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + signature));
    }

    private Signature signature(Class<?> repository, String signature) {
        return Signature.of(candidate(repository, signature), entities, method -> true);
    }

    @Test
    void theDeclaredMethodsByNameThenTheFiveInherited() {
        assertEquals(List.of("add(Gizmo)", "change(Gizmo)", "countByStockGreaterThan(int)", "deleteByName(String)",
                "empty(String)", "findByLevel(Level, PageRequest)", "findByName(String)", "findByNameIn(List)",
                "keep(Gizmo)", "purge()", "remove(Gizmo)", "search(String)", "search(String, int)",
                "delete(Gizmo)", "deleteById(Long)", "findAll()", "findAll(PageRequest, Order)", "findById(Long)",
                "save(Gizmo)"),
                RepositoryMethods.of(GizmoRepository.class).stream().map(RepositoryMethods.Candidate::signature)
                        .toList());
    }

    @Test
    void inheritedMethodsAreBoundToTheRepositorysTypes() {
        RepositoryMethods.Candidate save = candidate(GizmoRepository.class, "save(Gizmo)");
        assertEquals(List.of("entity"), save.names());
        assertEquals(List.of(Gizmo.class), save.types());
        assertEquals("Gizmo", save.returns());
        assertEquals(true, save.inherited());
        RepositoryMethods.Candidate findById = candidate(GizmoRepository.class, "findById(Long)");
        assertEquals(List.of("id"), findById.names());
        assertEquals("Optional<Gizmo>", findById.returns());
        assertEquals("Stream<Gizmo>", candidate(GizmoRepository.class, "findAll()").returns());
        assertEquals("List<Gizmo>", candidate(GizmoRepository.class, "search(String)").returns());
        assertEquals(false, candidate(GizmoRepository.class, "search(String)").inherited());
    }

    @Test
    void theSchemaOfEachParameterAllRequired() {
        assertEquals("{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"},"
                + "\"min\":{\"type\":\"integer\"}},\"required\":[\"pattern\",\"min\"]}",
                signature(GizmoRepository.class, "search(String, int)").schema());
        assertEquals("{\"type\":\"object\",\"properties\":{},\"required\":[]}",
                signature(GizmoRepository.class, "purge()").schema());
        assertEquals("{\"type\":\"object\",\"properties\":{\"gizmo\":" + EntityJsonTest.GIZMO_SCHEMA
                + "},\"required\":[\"gizmo\"]}", signature(GizmoRepository.class, "keep(Gizmo)").schema());
        assertNull(signature(GizmoRepository.class, "search(String)").reason());
    }

    @Test
    void theSaveOfATaskDescribesItsEntityForTheForm() {
        assertEquals("{\"type\":\"object\",\"properties\":{\"entity\":" + EntityJsonTest.TASK_SCHEMA
                + "},\"required\":[\"entity\"]}", signature(TaskRepository.class, "save(Task)").schema());
    }

    @Test
    void anUnsupportedParameterMakesTheMethodNotRunnable() {
        Signature page = signature(GizmoRepository.class, "findByLevel(Level, PageRequest)");
        assertEquals("parameter page: PageRequest is not supported", page.reason());
        assertNull(page.schema());
        assertEquals("parameter names: List is not supported",
                signature(GizmoRepository.class, "findByNameIn(List)").reason());
        assertEquals("parameter pageRequest: PageRequest is not supported",
                signature(GizmoRepository.class, "findAll(PageRequest, Order)").reason());
    }

    @Test
    void anEntityWithoutAModelMakesTheMethodNotRunnable() {
        assertEquals("parameter entity: Broken has no model (io.vidocq.mansart.data.core.MansartDataException)",
                signature(BrokenRepository.class, "save(Broken)").reason());
    }

    @Test
    void aPackageNotOpenMakesTheMethodNotRunnable() {
        Signature closed = Signature.of(candidate(GizmoRepository.class, "search(String)"), entities, m -> false);

        assertEquals("package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev not open to Vidocq",
                closed.reason());
    }

    @Test
    void theArgumentsInTheirOrder() throws Exception {
        assertArrayEquals(new Object[] {"%a%", 2}, signature(GizmoRepository.class, "search(String, int)")
                .arguments(Json.parse("{\"min\":2,\"pattern\":\"%a%\"}")));
        Object[] kept = signature(GizmoRepository.class, "keep(Gizmo)")
                .arguments(Json.parse("{\"gizmo\":{\"name\":\"bolt\"}}"));
        assertEquals("bolt", ((Gizmo) kept[0]).name());
        assertArrayEquals(new Object[0], signature(GizmoRepository.class, "purge()").arguments(Json.parse("{}")));
    }

    @Test
    void eachFailureNamesItsArgument() {
        Signature search = signature(GizmoRepository.class, "search(String, int)");

        assertEquals("min: missing", refusal(search, "{\"pattern\":\"a\"}"));
        assertEquals("max: unknown argument", refusal(search, "{\"pattern\":\"a\",\"min\":1,\"max\":2}"));
        assertEquals("arguments: not a JSON object", refusal(search, "[1]"));
        assertEquals("min: not an integer", refusal(search, "{\"pattern\":\"a\",\"min\":\"two\"}"));
    }

    private static String refusal(Signature signature, String json) {
        return assertThrows(ArgumentException.class, () -> signature.arguments(Json.parse(json))).getMessage();
    }
}

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

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which methods write (spec §3): each rule, and reads that look close. */
class WriteKindsTest {

    interface Samples {
        Gizmo save(Gizmo gizmo);

        List<Gizmo> saveAll(List<Gizmo> gizmos);

        Gizmo insertNew(Gizmo gizmo);

        long updateStock(int stock);

        void deleteEverything();

        long deleteByName(String name);

        @Insert
        Gizmo add(Gizmo gizmo);

        @Update
        Gizmo change(Gizmo gizmo);

        @Delete
        void remove(Gizmo gizmo);

        @Save
        Gizmo keep(Gizmo gizmo);

        @Query("  update Gizmo SET stock = 0")
        long empty();

        @Query("\n\tDELETE FROM Gizmo")
        long purge();

        @Query("FROM Gizmo WHERE name = :n")
        List<Gizmo> named(@Param("n") String n);

        @Query("SELECT count(this) FROM Gizmo")
        long total();

        List<Gizmo> findByName(String name);

        long countByStock(int stock);

        Gizmo saved();
    }

    private static final Set<String> WRITES = Set.of("save", "saveAll", "insertNew", "updateStock",
            "deleteEverything", "deleteByName", "add", "change", "remove", "keep", "empty", "purge");

    @Test
    void everyRuleOfSection3AndNothingElse() {
        for (Method method : Samples.class.getDeclaredMethods()) {
            assertEquals(WRITES.contains(method.getName()), WriteKinds.isWrite(method), method.getName());
        }
    }

    @Test
    void theInheritedWrites() throws Exception {
        assertTrue(WriteKinds.isWrite(BasicRepository.class.getMethod("save", Object.class)));
        assertTrue(WriteKinds.isWrite(BasicRepository.class.getMethod("deleteById", Object.class)));
        assertTrue(WriteKinds.isWrite(BasicRepository.class.getMethod("delete", Object.class)));
        assertFalse(WriteKinds.isWrite(BasicRepository.class.getMethod("findById", Object.class)));
        assertFalse(WriteKinds.isWrite(BasicRepository.class.getMethod("findAll")));
    }
}

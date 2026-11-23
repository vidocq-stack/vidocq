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
package io.vidocq.grimm.cdi;

import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrimmExtensionTest {

    @Test
    void creatorResolvesTheRecordedNamesAndSkipsUnknownOnes() {
        var created = new ScannedTypesCreator().create(null, new Parameters() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(String key, Class<T> type) {
                return (T) new String[] {GrimmExtensionTest.class.getName(), String.class.getName(), "com.example.Missing"};
            }

            @Override
            public <T> T get(String key, Class<T> type, T defaultValue) {
                return get(key, type);
            }
        });

        assertTrue(created.classes().contains(GrimmExtensionTest.class));
        assertTrue(created.classes().contains(String.class));
        assertFalse(created.classes().stream().map(Class::getName).toList().contains("com.example.Missing"));
    }

    /**
     * BUG-20261123-50: the extension takes no monitor, neither a {@code synchronized} method or block nor a
     * {@code Collections.synchronized*} wrapper: the project rules forbid them ({@code CLAUDE.md}, "No
     * {@code synchronized}"), virtual threads first.
     */
    @Test
    void extensionTakesNoMonitor() throws IOException {
        byte[] bytes;
        try (InputStream in = GrimmExtension.class.getResourceAsStream("GrimmExtension.class")) {
            assertNotNull(in, "GrimmExtension.class must be readable");
            bytes = in.readAllBytes();
        }
        ClassModel model = ClassFile.of().parse(bytes);
        List<String> monitors = new ArrayList<>();
        for (MethodModel method : model.methods()) {
            if (method.flags().has(AccessFlag.SYNCHRONIZED)) {
                monitors.add(method.methodName().stringValue() + ": synchronized method");
            }
            method.code().ifPresent(code -> code.forEach(element -> {
                if (element instanceof MonitorInstruction) {
                    monitors.add(method.methodName().stringValue() + ": synchronized block");
                } else if (element instanceof InvokeInstruction invoke
                        && invoke.name().stringValue().startsWith("synchronized")
                        && invoke.owner().asInternalName().equals("java/util/Collections")) {
                    monitors.add(method.methodName().stringValue() + ": Collections." + invoke.name().stringValue());
                }
            }));
        }
        assertEquals(List.of(), monitors);
    }
}

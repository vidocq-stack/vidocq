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
package io.vidocq.vauban.indexer.model;

import java.util.Objects;

/**
 * Test-scope compatibility shim for vauban-indexer descriptor parsing.
 *
 * It keeps the same FQCN/API as the runtime class and adds support for array
 * descriptors (e.g. [Lcom/acme/Foo;), which appear in MP OpenAPI TCK classes.
 */
public record DotName(String value) implements Comparable<DotName> {

    public DotName {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("value must not be empty");
        }
    }

    public static DotName of(String value) {
        return new DotName(value);
    }

    public static DotName fromInternal(String internalName) {
        return new DotName(internalName.replace('/', '.'));
    }

    public static DotName fromDescriptor(String descriptor) {
        String normalized = Objects.requireNonNull(descriptor, "descriptor must not be null");

        // Unwrap any array prefix to reach the component descriptor.
        while (normalized.startsWith("[")) {
            normalized = normalized.substring(1);
        }

        if (normalized.startsWith("L") && normalized.endsWith(";")) {
            return fromInternal(normalized.substring(1, normalized.length() - 1));
        }

        return switch (normalized) {
            case "Z" -> new DotName("boolean");
            case "B" -> new DotName("byte");
            case "C" -> new DotName("char");
            case "D" -> new DotName("double");
            case "F" -> new DotName("float");
            case "I" -> new DotName("int");
            case "J" -> new DotName("long");
            case "S" -> new DotName("short");
            default -> throw new IllegalArgumentException("Invalid class descriptor: " + descriptor);
        };
    }

    public String simpleName() {
        int idx = value.lastIndexOf('.');
        return idx < 0 ? value : value.substring(idx + 1);
    }

    public String packageName() {
        int idx = value.lastIndexOf('.');
        return idx < 0 ? "" : value.substring(0, idx);
    }

    public String toInternal() {
        return value.replace('.', '/');
    }

    public String toDescriptor() {
        return "L" + toInternal() + ";";
    }

    @Override
    public int compareTo(DotName other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}


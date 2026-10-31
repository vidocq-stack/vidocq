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
package io.vidocq.runtime.tooling;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The project {@link ProjectScaffolder} writes, as {@code vidocq create} and {@code vidocq:create} describe it
 * (Vidocq/vidocq#314).
 *
 * @param name          the project directory and artifactId
 * @param groupId       the Maven groupId
 * @param pkg           the root Java package, which is also the module name
 * @param extensions    the catalog ids of the extensions to enable, written in this order
 * @param parentVersion the {@code vidocq-runtime-parent} version, or {@code null} for the tool's runtime version
 * @param main          whether to write an {@code @VidocqMain} trampoline
 * @param openModule    whether {@code module-info.java} declares an {@code open module}, which opens every package
 *                      and so needs no {@code opens} line
 */
public record ProjectSpec(String name, String groupId, String pkg, Set<String> extensions, String parentVersion,
                          boolean main, boolean openModule) {

    /** The groupId of a project that names none. */
    public static final String DEFAULT_GROUP_ID = "io.example";

    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "_",
            "true", "false", "null");

    public ProjectSpec {
        // In the order given: Set.copyOf iterates in an order that changes from one JVM to the next
        // (BUG-20261024-01).
        extensions = Collections.unmodifiableSequencedSet(new LinkedHashSet<>(extensions));
    }

    /** A spec whose module is not open. */
    public ProjectSpec(String name, String groupId, String pkg, Set<String> extensions, String parentVersion,
                       boolean main) {
        this(name, groupId, pkg, extensions, parentVersion, main, false);
    }

    /** {@link #of(String, String, String, Collection, String, boolean, boolean, String)} for a module that is not open. */
    public static ProjectSpec of(String name, String groupId, String pkg, Collection<String> extensions,
                                 String parentVersion, boolean main, String packageOption) {
        return of(name, groupId, pkg, extensions, parentVersion, main, false, packageOption);
    }

    /**
     * The spec with its defaults: the groupId {@value #DEFAULT_GROUP_ID}, the package {@code <groupId>.<name>}.
     *
     * @param packageOption how the tool names its package option, for the error message ({@code --package <pkg>})
     * @throws IllegalArgumentException when the package, given or derived, is not a valid Java package name, or an
     *                                  extension is neither a catalog id nor a {@code groupId:artifactId}
     */
    public static ProjectSpec of(String name, String groupId, String pkg, Collection<String> extensions,
                                 String parentVersion, boolean main, boolean openModule, String packageOption) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a project needs a name");
        }
        String gid = groupId != null ? groupId : DEFAULT_GROUP_ID;
        String p = pkg != null ? pkg : gid + "." + name.replace('-', '.');
        if (!isPackageName(p)) {
            throw new IllegalArgumentException("'" + p + "' is not a valid Java package name"
                    + (pkg != null ? "." : " (derived from the groupId and the name).")
                    + " It is also the module name, so each dot-separated part must be a Java"
                    + " identifier that is not a keyword; choose another with " + packageOption + ".");
        }
        for (String id : extensions) {
            try {
                KnownExtensions.resolve(id);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown extension '" + id + "': " + e.getMessage(), e);
            }
        }
        Set<String> ids = new LinkedHashSet<>(KnownExtensions.withRequired(extensions));
        ids.addAll(KnownExtensions.companions(ids));
        return new ProjectSpec(name, gid, p, ids, parentVersion, main, openModule);
    }

    /** Whether {@code name} is a qualified Java name usable as a package and module name. */
    public static boolean isPackageName(String name) {
        for (String segment : name.split("\\.", -1)) {
            if (segment.isEmpty() || RESERVED.contains(segment)
                    || !Character.isJavaIdentifierStart(segment.codePointAt(0))
                    || !segment.codePoints().allMatch(Character::isJavaIdentifierPart)) {
                return false;
            }
        }
        return true;
    }
}

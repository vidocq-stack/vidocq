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
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.core.ReloadRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;

/**
 * Classifies a recompile (design §2): nothing to reload, a redefinition of method bodies, or a swap of the
 * application layer with its reason. The rules are tried in the design's order, across every changed file, and the
 * first that matches decides.
 */
final class HotSwapClassifier {

    private static final String BUILD_INFO = "META-INF/vidocq/build-info/";
    private static final String MODULE_INFO = "module-info.class";
    private static final String CLASS = ".class";

    private final List<Path> projectSourceRoots;
    private final StartTimeDeclarations declarations;

    /** A file of the compiled output: its class root and its path relative to it. */
    private record File(Path root, String path) {
        boolean isClass() {
            return path.endsWith(CLASS);
        }

        String simpleName() {
            String name = path.substring(path.lastIndexOf('/') + 1);
            return name.substring(0, name.length() - CLASS.length());
        }
    }

    /** A changed class, before and after. */
    private record Change(File file, ClassFingerprint old, ClassFingerprint now) {}

    /**
     * @param projectSourceRoots the compile source roots outside the build directory: a class is the project's own
     *                           when its package and {@code SourceFile} are found in one of them
     */
    HotSwapClassifier(List<Path> projectSourceRoots, StartTimeDeclarations declarations) {
        this.projectSourceRoots = List.copyOf(projectSourceRoots);
        this.declarations = declarations;
    }

    /** Empty: nothing to reload (rule 1). */
    Optional<ReloadRequest> classify(ClassesSnapshot base, ClassesSnapshot next, SupertypeIndex supertypes) {
        var before = files(base);
        var after = files(next);
        // Rule 2: a file added or removed.
        for (File file : after.keySet()) {
            if (!before.containsKey(file)) {
                return swap(file.isClass() ? file.simpleName() + " is a new class" : file.path() + " was added");
            }
        }
        for (File file : before.keySet()) {
            if (!after.containsKey(file)) {
                return swap(file.isClass() ? file.simpleName() + " was deleted" : file.path() + " was removed");
            }
        }
        List<File> changed = after.keySet().stream()
                .filter(file -> !after.get(file).sha256().equals(before.get(file).sha256()))
                .toList();
        if (changed.isEmpty()) {
            return Optional.empty();
        }
        // Rule 3: a resource or module-info.
        for (File file : changed) {
            if (file.path().endsWith(MODULE_INFO)) {
                return swap("module-info changed");
            }
            if (!file.isClass()) {
                return swap(file.path() + " changed");
            }
        }
        // Rule 4: not compiled from the project's own sources.
        var classes = new ArrayList<Change>();
        for (File file : changed) {
            var old = before.get(file).fingerprint();
            var now = after.get(file).fingerprint();
            if (old.isEmpty() || now.isEmpty() || !ownSource(now.get())) {
                return swap(file.simpleName() + " is not compiled from this project's sources");
            }
            classes.add(new Change(file, old.get(), now.get()));
        }
        // Rule 5: shape.
        for (Change change : classes) {
            var difference = change.now().firstShapeDifference(change.old());
            if (difference.isPresent()) {
                return swap(change.file().simpleName() + " " + difference.get());
            }
        }
        // Rule 6: start-time code.
        Set<String> startTime = StartTimeCode.of(next, declarations, supertypes);
        SequencedMap<String, SequencedSet<String>> methodsByClass = new LinkedHashMap<>();
        for (Change change : classes) {
            ClassFingerprint now = change.now();
            for (String key : now.methodKeysWithChangedCode(change.old())) {
                if (startTime.contains(now.binaryName() + "#" + key)) {
                    return swap(ranAtStart(change.file().simpleName(), key));
                }
            }
            var methods = now.methodsWithChangedCode(change.old());
            if (!methods.isEmpty()) {
                methodsByClass.put(now.binaryName(), new LinkedHashSet<>(methods));
            }
        }
        // Rule 7: redefine; classes whose bytes differ with an equal fingerprint are left alone.
        return methodsByClass.isEmpty() ? Optional.empty()
                : Optional.of(new ReloadRequest.Redefine(methodsByClass));
    }

    private boolean ownSource(ClassFingerprint fingerprint) {
        if (fingerprint.sourceFile().isEmpty()) {
            return false;
        }
        String binaryName = fingerprint.binaryName();
        int dot = binaryName.lastIndexOf('.');
        String file = (dot < 0 ? "" : binaryName.substring(0, dot).replace('.', '/') + "/") + fingerprint.sourceFile();
        return projectSourceRoots.stream().anyMatch(root -> Files.isRegularFile(root.resolve(file)));
    }

    private static String ranAtStart(String simpleName, String methodKey) {
        String method = methodKey.substring(0, methodKey.indexOf('('));
        return switch (method) {
            case "<clinit>" -> simpleName + "'s static initializer ran at start";
            case "<init>" -> simpleName + "'s constructor ran at start";
            default -> simpleName + "." + method + "() ran at start";
        };
    }

    /** Every file of every root, build-info left out. */
    private static Map<File, ClassesSnapshot.Entry> files(ClassesSnapshot snapshot) {
        var files = new LinkedHashMap<File, ClassesSnapshot.Entry>();
        snapshot.byRoot().forEach((root, entries) -> entries.forEach((path, entry) -> {
            if (!path.startsWith(BUILD_INFO)) {
                files.put(new File(root, path), entry);
            }
        }));
        return files;
    }

    private static Optional<ReloadRequest> swap(String reason) {
        return Optional.of(new ReloadRequest.Swap(reason));
    }
}

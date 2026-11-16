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
        List<File> added = after.keySet().stream().filter(file -> !before.containsKey(file)).toList();
        List<File> removed = before.keySet().stream().filter(file -> !after.containsKey(file)).toList();
        List<File> changed = after.keySet().stream().filter(before::containsKey)
                .filter(file -> !after.get(file).sha256().equals(before.get(file).sha256()))
                .toList();
        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) {
            return Optional.empty();
        }
        // A generated class (a client proxy, a bean index) changes because a class of the project did: when one of
        // the project's classes explains the swap, the reason names it.
        Optional<String> ownClass = ownClassReason(added, changed, before, after);
        Optional<String> other = addedRemovedResourceOrGenerated(added, removed, changed, before, after);
        if (other.isPresent()) {
            return swap(ownClass.orElseGet(other::get));
        }
        // Rule 5: shape. Every changed file is now a class compiled from the project's own sources.
        if (ownClass.isPresent()) {
            return swap(ownClass.get());
        }
        List<Change> classes = changed.stream().map(file -> new Change(file,
                before.get(file).fingerprint().orElseThrow(), after.get(file).fingerprint().orElseThrow())).toList();
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

    /** A class of the project's own sources that is new, or whose shape changed (rules 2 and 5). */
    private Optional<String> ownClassReason(List<File> added, List<File> changed,
            Map<File, ClassesSnapshot.Entry> before, Map<File, ClassesSnapshot.Entry> after) {
        for (File file : added) {
            if (ownClass(file, after.get(file))) {
                return Optional.of(file.simpleName() + " is a new class");
            }
        }
        for (File file : changed) {
            var old = before.get(file).fingerprint();
            if (old.isPresent() && ownClass(file, after.get(file))) {
                var difference = after.get(file).fingerprint().orElseThrow().firstShapeDifference(old.get());
                if (difference.isPresent()) {
                    return Optional.of(file.simpleName() + " " + difference.get());
                }
            }
        }
        return Optional.empty();
    }

    private boolean ownClass(File file, ClassesSnapshot.Entry entry) {
        return file.isClass() && !file.path().endsWith(MODULE_INFO)
                && entry.fingerprint().map(this::ownSource).orElse(false);
    }

    /** Rules 2 to 4: a file added or removed, a resource or module-info changed, a class not of the project's sources. */
    private Optional<String> addedRemovedResourceOrGenerated(List<File> added, List<File> removed, List<File> changed,
            Map<File, ClassesSnapshot.Entry> before, Map<File, ClassesSnapshot.Entry> after) {
        if (!added.isEmpty()) {
            File file = added.getFirst();
            return Optional.of(file.isClass() ? file.simpleName() + " is a new class" : file.path() + " was added");
        }
        if (!removed.isEmpty()) {
            File file = removed.getFirst();
            return Optional.of(file.isClass() ? file.simpleName() + " was deleted" : file.path() + " was removed");
        }
        for (File file : changed) {
            if (file.path().endsWith(MODULE_INFO)) {
                return Optional.of("module-info changed");
            }
            if (!file.isClass()) {
                return Optional.of(file.path() + " changed");
            }
        }
        for (File file : changed) {
            var old = before.get(file).fingerprint();
            var now = after.get(file).fingerprint();
            if (old.isEmpty() || now.isEmpty() || !ownSource(now.get())) {
                return Optional.of(file.simpleName() + " is not compiled from this project's sources");
            }
        }
        return Optional.empty();
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

    /** Every file of every root, the files the build writes for its own use left out. */
    private Map<File, ClassesSnapshot.Entry> files(ClassesSnapshot snapshot) {
        var files = new LinkedHashMap<File, ClassesSnapshot.Entry>();
        snapshot.byRoot().forEach((root, entries) -> entries.forEach((path, entry) -> {
            if (!buildFile(path)) {
                files.put(new File(root, path), entry);
            }
        }));
        return files;
    }

    /** Build-info, or a file or directory a jar declares in {@code build-files} (rule 3's exception). */
    private boolean buildFile(String path) {
        return path.startsWith(BUILD_INFO) || declarations.buildFiles().stream()
                .anyMatch(file -> file.endsWith("/") ? path.startsWith(file) : path.equals(file));
    }

    private static Optional<ReloadRequest> swap(String reason) {
        return Optional.of(new ReloadRequest.Swap(reason));
    }
}

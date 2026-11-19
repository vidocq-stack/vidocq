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

import io.vidocq.runtime.tooling.ModuleInfoEditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The plugin's side of the runtime's module-access diagnostic (BUG-20261031-15). Under {@code vidocq dev}, when a
 * library fails to reach a package of the application by reflection, the application writes that package to
 * {@code target/vidocq-dev-opens.request}; the plugin opens it in {@code module-info.java}, in the block
 * {@code vidocq doctor --fix} keeps, and the source watcher's next round recompiles and reloads the application.
 * Only package names are read from the file: nothing else of it reaches the source.
 */
final class OpensRequestFile implements AutoCloseable {

    /** The request file's name, in the build directory. */
    static final String FILE_NAME = "vidocq-dev-opens.request";
    /** The system property the application reads it from; same name in {@code io.vidocq.runtime.core}. */
    static final String PROPERTY = "vidocq.dev.opens.file";
    /** How often the file is looked for. */
    static final long POLL_MILLIS = 250;

    private final Path file;
    private final Path moduleInfo;
    private final Consumer<String> info;
    private final Consumer<String> warn;
    private final AtomicReference<Thread> poller = new AtomicReference<>();

    /**
     * @param moduleInfo the application's {@code module-info.java}, or {@code null} when it has none
     */
    OpensRequestFile(Path file, Path moduleInfo, Consumer<String> info, Consumer<String> warn) {
        this.file = file;
        this.moduleInfo = moduleInfo;
        this.info = info;
        this.warn = warn;
    }

    /** Reads and deletes a pending request, then opens its packages; nothing when there is none. */
    synchronized void apply() {
        Set<String> packages = take();
        if (packages.isEmpty()) {
            return;
        }
        if (moduleInfo == null || !Files.isRegularFile(moduleInfo)) {
            packages.forEach(pkg -> warn.accept("module-info.java not found: add `opens " + pkg
                    + ";` to the application's module descriptor"));
            return;
        }
        try {
            String source = Files.readString(moduleInfo, StandardCharsets.UTF_8);
            String opened = ModuleInfoEditor.openForReflection(source, packages);
            if (opened.equals(source)) {
                return;
            }
            Files.writeString(moduleInfo, opened, StandardCharsets.UTF_8);
            packages.stream().filter(pkg -> !source.contains("opens " + pkg + ";"))
                    .forEach(pkg -> info.accept("✔ module-info.java: opens " + pkg
                            + ";   (reached by reflection at run time)"));
        } catch (IOException | RuntimeException e) {
            warn.accept("Cannot open " + packages + " in " + moduleInfo + ": " + e.getMessage());
        }
    }

    private Set<String> take() {
        Set<String> packages = new LinkedHashSet<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String pkg = line.strip();
                if (isPackageName(pkg)) {
                    packages.add(pkg);
                }
            }
            Files.deleteIfExists(file);
        } catch (IOException _) {
            // no request (the file is absent) or none readable yet: looked for again on the next poll
        }
        return packages;
    }

    /** Whether {@code name} is a Java package name: dot-separated identifiers, checked without a regular expression. */
    static boolean isPackageName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (String part : name.split("\\.", -1)) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) {
                return false;
            }
            for (int i = 1; i < part.length(); i++) {
                if (!Character.isJavaIdentifierPart(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Deletes a request an earlier session left, then looks for one every {@value #POLL_MILLIS} ms on the daemon
     * thread {@code vidocq-dev-opens}.
     */
    static OpensRequestFile poll(Path file, Path moduleInfo, Consumer<String> info, Consumer<String> warn)
            throws IOException {
        Files.deleteIfExists(file);
        OpensRequestFile requests = new OpensRequestFile(file, moduleInfo, info, warn);
        requests.poller.set(Thread.ofVirtual().name("vidocq-dev-opens").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                requests.apply();
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }));
        return requests;
    }

    /** Stops polling; idempotent. */
    @Override
    public void close() {
        Thread running = poller.getAndSet(null);
        if (running != null) {
            running.interrupt();
            try {
                running.join(1_000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

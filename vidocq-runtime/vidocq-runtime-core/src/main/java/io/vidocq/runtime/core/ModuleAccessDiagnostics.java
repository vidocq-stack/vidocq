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
package io.vidocq.runtime.core;

import java.io.IOException;
import java.lang.reflect.InaccessibleObjectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Names the {@code opens} line an application is missing when a library reaches one of its packages by reflection at
 * run time (BUG-20261031-15). The build checks ({@code vidocq:check-module-info}, {@code vidocq doctor}) find the
 * packages the runtime is known to reflect on; a JSON-B call made by hand or a third-party library decides at run
 * time, and fails with an {@link InaccessibleObjectException} or an {@link IllegalAccessException} whose message
 * says {@code module shop does not "opens shop.book"}. Installed on the root logger, this handler reads the cause
 * chain of every logged failure — a request's 500, a failed boot — and logs, once per package, which line to add.
 * Under {@code vidocq dev} it also writes the package to a request file the plugin reads: the plugin adds the line
 * to {@code module-info.java}, and the change reloads the application.
 *
 * <p>Only a package of the application's own module is named: a denial between two libraries, or into the JDK, is
 * left to the stack trace.</p>
 */
final class ModuleAccessDiagnostics extends Handler {

    /** The system property naming the request file, set by {@code vidocq:dev} for the application it starts. */
    static final String OPENS_FILE_PROPERTY = "vidocq.dev.opens.file";
    /** The request file's name, in the build directory. */
    static final String OPENS_FILE_NAME = "vidocq-dev-opens.request";

    private static final System.Logger LOG = System.getLogger(ModuleAccessDiagnostics.class.getName());
    /** {@code AccessibleObject.setAccessible}: {@code Unable to make <what> accessible: module m does not "opens p" to <reader>}. */
    private static final String UNABLE = "Unable to make ";
    private static final String ACCESSIBLE = " accessible: module ";
    /** {@code Reflection.newIllegalAccessException}: {@code <reader> cannot access <what> (in module m) because module m does not export p to <reader>}. */
    private static final String CANNOT_ACCESS = " cannot access ";
    private static final String IN_MODULE = " (in module ";
    private static final String BECAUSE = ") because module ";
    private static final String DOES_NOT = " does not ";
    private static final String TO = " to ";
    private static final int MAX_CAUSES = 32;

    private static Handler installed;

    private final Predicate<String> applicationModule;
    private final Path devOpensFile;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /**
     * @param applicationModule whether a module name is the application's
     * @param devOpensFile      the request file of {@code vidocq dev}, or {@code null} outside it
     */
    ModuleAccessDiagnostics(Predicate<String> applicationModule, Path devOpensFile) {
        this.applicationModule = applicationModule;
        this.devOpensFile = devOpensFile;
    }

    /**
     * Adds the handler to the root logger, once per JVM. An application module is the one {@link Vidocq} knows, or a
     * module of the application layer (under {@code vidocq dev}, the application runs there without calling
     * {@code Vidocq.run}) that is not a module of the JDK, of Jakarta or of Vidocq.
     */
    static synchronized void install() {
        if (installed != null) {
            return;
        }
        String file = System.getProperty(OPENS_FILE_PROPERTY, "").strip();
        installed = new ModuleAccessDiagnostics(ModuleAccessDiagnostics::isApplicationModule,
                file.isEmpty() ? null : Path.of(file));
        Logger.getLogger("").addHandler(installed);
    }

    static boolean isApplicationModule(String name) {
        Module app = Vidocq.applicationModule();
        if (app != null && app.getName().equals(name)) {
            return true;
        }
        ModuleLayer layer = VidocqAppLayer.installedLayer();
        return layer != null && layer.findModule(name).isPresent() && !platformModule(name);
    }

    static boolean platformModule(String name) {
        return name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("jakarta.")
                || name.startsWith("io.vidocq.");
    }

    /** A library denied access to a package of the application's module. */
    record Denial(String module, String pkg, String reader, String what) {

        /** The error to log; {@code dev} when {@code vidocq dev} writes the line itself. */
        String message(boolean dev) {
            String head = "Module " + module + " does not open package " + pkg + " to " + reader
                    + ", which reached " + what + " by reflection at run time. ";
            if (dev) {
                return head + "vidocq dev adds `opens " + pkg + ";` to module-info.java and reloads the application:"
                        + " retry once it has reloaded.";
            }
            return head + "The build checks cannot see this use: add `opens " + pkg + ";` to module-info.java"
                    + " (or make it an `open module`). See \"Java modules in a Vidocq application\" in the"
                    + " documentation.";
        }
    }

    /** The first denial into the application's module in the cause chain of {@code failure}, if any. */
    static Optional<Denial> find(Throwable failure, Predicate<String> applicationModule) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.size() < MAX_CAUSES && seen.add(t); t = t.getCause()) {
            if (t instanceof InaccessibleObjectException || t instanceof IllegalAccessException) {
                Optional<Denial> denial = parse(t.getMessage()).filter(d -> applicationModule.test(d.module()));
                if (denial.isPresent()) {
                    return denial;
                }
            }
        }
        return Optional.empty();
    }

    /** The denial a JDK access message describes; read with plain string searches, the message being untrusted text. */
    static Optional<Denial> parse(String message) {
        if (message == null) {
            return Optional.empty();
        }
        Optional<Denial> inaccessible = parseInaccessible(message);
        return inaccessible.isPresent() ? inaccessible : parseIllegal(message);
    }

    /** {@code Unable to make <what> accessible: module <m> does not "opens <p>" to <reader>}. */
    private static Optional<Denial> parseInaccessible(String message) {
        int start = message.indexOf(UNABLE);
        int accessible = start < 0 ? -1 : message.indexOf(ACCESSIBLE, start);
        if (accessible < 0) {
            return Optional.empty();
        }
        String what = message.substring(start + UNABLE.length(), accessible);
        int moduleStart = accessible + ACCESSIBLE.length();
        int doesNot = message.indexOf(DOES_NOT + "\"", moduleStart);
        if (doesNot < 0) {
            return Optional.empty();
        }
        String module = message.substring(moduleStart, doesNot);
        int directive = doesNot + DOES_NOT.length() + 1;
        int space = message.indexOf(' ', directive);
        int quote = space < 0 ? -1 : message.indexOf('"', space);
        if (quote < 0) {
            return Optional.empty();
        }
        String pkg = message.substring(space + 1, quote);
        int to = message.indexOf(TO, quote);
        String reader = to < 0 ? null : message.substring(to + TO.length());
        return denial(module, pkg, reader, what);
    }

    /** {@code <reader> cannot access <what> (in module <m>) because module <m> does not export <p> to <reader>}. */
    private static Optional<Denial> parseIllegal(String message) {
        int cannot = message.indexOf(CANNOT_ACCESS);
        int in = cannot < 0 ? -1 : message.indexOf(IN_MODULE, cannot);
        int because = in < 0 ? -1 : message.indexOf(BECAUSE, in);
        if (because < 0) {
            return Optional.empty();
        }
        String what = message.substring(cannot + CANNOT_ACCESS.length(), in);
        String module = message.substring(in + IN_MODULE.length(), because);
        int doesNot = message.indexOf(DOES_NOT, because);
        if (doesNot < 0) {
            return Optional.empty();
        }
        int verbEnd = message.indexOf(' ', doesNot + DOES_NOT.length());
        if (verbEnd < 0) {
            return Optional.empty();
        }
        int to = message.indexOf(TO, verbEnd);
        String pkg = to < 0 ? message.substring(verbEnd + 1) : message.substring(verbEnd + 1, to);
        String reader = to < 0 ? null : message.substring(to + TO.length());
        return denial(module, pkg, reader, what);
    }

    private static Optional<Denial> denial(String module, String pkg, String reader, String what) {
        if (module.isBlank() || pkg.isBlank() || module.contains(" ") || pkg.contains(" ")) {
            return Optional.empty();
        }
        return Optional.of(new Denial(module, pkg, reader(reader), what));
    }

    /** {@code module io.x} → {@code io.x}; an unnamed module → the class path. */
    private static String reader(String described) {
        if (described == null || described.startsWith("unnamed module")) {
            return "a class on the class path";
        }
        return described.startsWith("module ") ? described.substring("module ".length()) : described;
    }

    @Override
    public void publish(LogRecord logRecord) {
        Throwable thrown = logRecord == null ? null : logRecord.getThrown();
        if (thrown == null) {
            return;
        }
        find(thrown, applicationModule).ifPresent(this::report);
    }

    private void report(Denial denial) {
        if (!reported.add(denial.module() + "/" + denial.pkg())) {
            return;
        }
        boolean dev = devOpensFile != null && request(denial.pkg());
        LOG.log(System.Logger.Level.ERROR, denial.message(dev));
    }

    /** Appends the package to the request file of {@code vidocq dev}; whether it could. */
    private boolean request(String pkg) {
        try {
            Path parent = devOpensFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(devOpensFile, pkg + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException _) {
            return false;
        }
    }

    @Override
    public void flush() {
        // nothing buffered
    }

    @Override
    public void close() {
        // nothing held
    }
}

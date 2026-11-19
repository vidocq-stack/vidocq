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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Gathers the {@link DoctorContext} of a project: the running JDK, {@code JAVA_HOME}, the Maven wrapper, the pom,
 * {@code vidocq.properties} and the extensions. {@code vidocq doctor} and {@code vidocq:doctor} both gather it here,
 * so they diagnose the same facts (Vidocq/vidocq#314); only how each finds the extensions differs.
 */
public final class DoctorFacts {

    private DoctorFacts() {}

    /**
     * How many extensions the project runs with, or why they could not be found.
     *
     * @param count the {@code VidocqExtension} providers on the runtime class path, transitive ones included
     * @param error why the dependencies could not be resolved, or {@code null}
     */
    public record Extensions(int count, String error) {

        public static Extensions found(int count) {
            return new Extensions(count, null);
        }

        public static Extensions failed(String error) {
            return new Extensions(0, error);
        }
    }

    /**
     * The facts about the project in {@code projectDir}, {@code javaHome} being the {@code JAVA_HOME} the tool sees
     * (null when unset). {@code extensions} runs only for a Vidocq project, as it may be slow.
     */
    public static DoctorContext gather(Path projectDir, String javaHome, Supplier<Extensions> extensions) {
        Path pom = projectDir.resolve("pom.xml");
        boolean pomPresent = Files.isRegularFile(pom);
        Path config = ConfigFile.locate(projectDir, Files::isRegularFile).orElse(null);
        boolean vidocqProject = pomPresent && referencesVidocq(pom);
        Extensions found = vidocqProject ? extensions.get() : Extensions.found(0);
        return new DoctorContext(
                Runtime.version().feature(),
                System.getProperty("java.version"),
                Diagnostics.MINIMUM_JAVA_VERSION,
                javaHome,
                javaHome != null && Files.isDirectory(Path.of(javaHome)),
                hasMavenWrapper(projectDir),
                pomPresent,
                vidocqProject,
                found.count(),
                config != null,
                config == null ? List.of() : configKeys(config),
                found.error());
    }

    private static boolean referencesVidocq(Path pom) {
        try {
            return Files.readString(pom).contains("io.vidocq.runtime");
        } catch (IOException _) {
            return false;
        }
    }

    private static List<String> configKeys(Path config) {
        try {
            return List.copyOf(PropertiesText.entries(Files.readString(config)).keySet());
        } catch (IOException _) {
            return List.of();
        }
    }

    /**
     * Whether {@code port} can be bound on every interface, as the HTTP listener binds it by default: what
     * {@link ProjectDoctor#read} asks of {@code vidocq doctor} and {@code vidocq:doctor}.
     */
    public static boolean portFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException _) {
            return false;
        }
    }

    /** Whether {@code start} or one of its parents holds a Maven wrapper. */
    private static boolean hasMavenWrapper(Path start) {
        for (Path dir = start.toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("mvnw")) || Files.isRegularFile(dir.resolve("mvnw.cmd"))) {
                return true;
            }
        }
        return false;
    }
}

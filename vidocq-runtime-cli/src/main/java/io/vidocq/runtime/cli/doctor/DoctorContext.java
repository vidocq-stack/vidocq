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
package io.vidocq.runtime.cli.doctor;

import java.util.List;

/**
 * Pure inputs for {@link Diagnostics}. Every value is gathered by the (impure)
 * caller — JVM properties, environment, filesystem probes, ServiceLoader scan —
 * so that the diagnostics engine itself stays deterministic and unit-testable.
 *
 * @param javaFeatureVersion  running JVM feature version, e.g. {@code 25}
 * @param javaVersionString   full {@code java.version} string for display
 * @param minimumJavaVersion  minimum feature version Vidocq requires
 * @param javaHome            value of {@code JAVA_HOME}, or {@code null} if unset
 * @param javaHomeIsDirectory whether {@code javaHome} points to an existing directory
 * @param mavenWrapperPresent whether an {@code mvnw}/{@code mvnw.cmd} was found
 * @param pomPresent          whether a {@code pom.xml} exists in the working directory
 * @param vidocqProject       whether that {@code pom.xml} references the Vidocq runtime
 * @param extensionCount      number of {@code VidocqExtension} providers on the classpath
 * @param configPresent       whether a {@code vidocq.properties} file was found
 * @param configKeys          the keys declared in that file (empty when absent)
 */
public record DoctorContext(
        int javaFeatureVersion,
        String javaVersionString,
        int minimumJavaVersion,
        String javaHome,
        boolean javaHomeIsDirectory,
        boolean mavenWrapperPresent,
        boolean pomPresent,
        boolean vidocqProject,
        int extensionCount,
        boolean configPresent,
        List<String> configKeys
) {
    public DoctorContext {
        configKeys = configKeys == null ? List.of() : List.copyOf(configKeys);
    }

    /**
     * Backward-compatible constructor for callers that do not inspect config:
     * reports no {@code vidocq.properties} and no keys.
     */
    public DoctorContext(
            int javaFeatureVersion,
            String javaVersionString,
            int minimumJavaVersion,
            String javaHome,
            boolean javaHomeIsDirectory,
            boolean mavenWrapperPresent,
            boolean pomPresent,
            boolean vidocqProject,
            int extensionCount) {
        this(javaFeatureVersion, javaVersionString, minimumJavaVersion, javaHome,
                javaHomeIsDirectory, mavenWrapperPresent, pomPresent, vidocqProject,
                extensionCount, false, List.of());
    }

    public boolean javaHomeSet() {
        return javaHome != null && !javaHome.isBlank();
    }
}

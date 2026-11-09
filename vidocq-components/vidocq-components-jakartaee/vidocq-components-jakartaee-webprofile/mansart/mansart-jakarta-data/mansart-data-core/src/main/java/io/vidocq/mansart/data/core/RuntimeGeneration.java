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
package io.vidocq.mansart.data.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The repositories Mansart implements at run time because the build did not: an interface compiled without the
 * annotation processor ({@code mansart-data-processor}). That path is kept, but it is the exception: it costs startup
 * time and has no ahead-of-time equivalent (native image, CDS). So each repository served that way is announced by a
 * framed warning, once per JVM. A repository the run-time generator cannot implement fails with an exception that
 * names the cause: there is no {@link java.lang.reflect.Proxy} fallback.
 */
final class RuntimeGeneration {

    private static final System.Logger LOG = System.getLogger(RuntimeGeneration.class.getName());

    private static final String RULE = "=".repeat(100);

    private static final String FIX = "Fix: add mansart-data-processor to the annotation processor path of the module "
            + "that declares it.\n";

    private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

    private RuntimeGeneration() {
    }

    /** A repository implemented by a class generated at run time: warns unless a warning already named it. */
    static void generated(Class<?> repository) {
        if (ANNOUNCED.add(repository.getName())) {
            LOG.log(System.Logger.Level.WARNING, generatedWarning(repository.getName(), cause(repository)));
        }
    }

    /**
     * Why the build did not implement {@code repository}: a type no generated top-level class of its package can see
     * (private, or nested in a private type; a local one is refused before), or a module compiled without the
     * processor.
     */
    static String cause(Class<?> repository) {
        for (Class<?> type = repository; type != null; type = type.getEnclosingClass()) {
            if (java.lang.reflect.Modifier.isPrivate(type.getModifiers())) {
                return "It is not visible from its package (" + type.getName() + " is private), so no generated"
                        + " class can implement it. Make it, and the types it is nested in, at least package-private"
                        + " (the build said so too, unless -Amansart.privateRepositories=warn silenced it).\n";
            }
        }
        return """
                It was compiled without the Mansart annotation processor.
                """ + FIX;
    }

    static String generatedWarning(String repositoryName, String cause) {
        return "\n" + RULE + "\n"
                + "MANSART — RUNTIME CODE GENERATION FALLBACK\n"
                + "The repository " + repositoryName + " is implemented at run time instead of at build time.\n"
                + cause
                + """
                This fallback is exceptional: it slows the startup, cannot run ahead of time (native image, CDS) and,
                in a named module, reaches the repository's package by reflection.
                """
                + RULE;
    }
}

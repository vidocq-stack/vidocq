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
 * framed warning, once per JVM. A repository the run-time generator could not implement either, and that a
 * {@link java.lang.reflect.Proxy} serves instead, gets its own warning, which names the cause.
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
            LOG.log(System.Logger.Level.WARNING, generatedWarning(repository.getName()));
        }
    }

    /** A repository the generator failed on, served by a {@code Proxy}: warns unless a warning already named it. */
    static void proxied(Class<?> repository, Throwable cause) {
        if (ANNOUNCED.add(repository.getName())) {
            LOG.log(System.Logger.Level.WARNING, proxiedWarning(repository.getName(), cause), cause);
        }
    }

    static String generatedWarning(String repositoryName) {
        return "\n" + RULE + "\n"
                + "MANSART — RUNTIME CODE GENERATION FALLBACK\n"
                + "The repository " + repositoryName + " is implemented at run time instead of at build time.\n"
                + """
                It was compiled without the Mansart annotation processor. This fallback is exceptional: it slows the
                startup and cannot run ahead of time (native image, CDS).
                """
                + FIX
                + RULE;
    }

    static String proxiedWarning(String repositoryName, Throwable cause) {
        return "\n" + RULE + "\n"
                + "MANSART — DYNAMIC PROXY FALLBACK\n"
                + "The repository " + repositoryName + " is served by a java.lang.reflect.Proxy: the run-time\n"
                + "generator could not implement it (" + cause + ").\n"
                + """
                It was compiled without the Mansart annotation processor, and the run-time fallback failed too. Every
                call goes through reflection: this is the slowest path, and it cannot run ahead of time.
                """
                + FIX
                + RULE;
    }
}

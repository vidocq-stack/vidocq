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
package io.vidocq.runtime.it.humboldt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261113-02 (Vidocq/vidocq#546): with {@code vidocq-runtime-humboldt-telemetry-extension-codegen} on the
 * processor path, Vauban runs Humboldt's Build Compatible Extension when this module compiles, and freezes the
 * {@code @SpanBinding} it adds to each {@code @WithSpan} method in {@code META-INF/vauban-enhancements.properties}.
 * Without the bundle the file is not written: the binding exists only if the container runs the extension again
 * at each start.
 */
class SpanBindingFrozenAtBuildTest {

    private static final String PATCH = "META-INF/vauban-enhancements.properties";
    private static final String SPAN_BINDING = "io.vidocq.humboldt.cdi.SpanBinding";

    @Test
    void theSpanBindingOfEachWithSpanMethodIsInTheCompiledOutput() throws IOException, URISyntaxException {
        Path patch = compiledOutput().resolve(PATCH);
        assertTrue(Files.isRegularFile(patch), "the build froze no @Enhancement: " + patch + " is missing, so"
                + " HumboldtBuildCompatibleExtension did not run on the processor path");
        Properties frozen = new Properties();
        try (InputStream in = Files.newInputStream(patch)) {
            frozen.load(in);
        }

        String service = TraceTestService.class.getName();
        List<String> bound = frozen.stringPropertyNames().stream()
                .filter(target -> target.startsWith(service + "#method#"))
                .filter(target -> frozen.getProperty(target).contains(SPAN_BINDING))
                .map(target -> target.substring(target.lastIndexOf('#') + 1, target.indexOf('(')))
                .sorted()
                .toList();
        assertEquals(List.of("alwaysFails", "doWork"), bound, "the methods of " + service
                + " frozen with @SpanBinding, in " + frozen);
    }

    /**
     * Vidocq/vauban BUG-20261113-13: the build also generates the subclass that intercepts those methods, so the
     * container does not define it at start. The source carries no binding: the extension adds it.
     */
    @Test
    void theServiceTheExtensionBindsGetsItsInterceptedSubclassAtBuild() throws URISyntaxException {
        Path subclass = compiledOutput().resolve(TraceTestService.class.getName().replace('.', '/')
                + "$$Intercepted.class");
        assertTrue(Files.isRegularFile(subclass), "the build generated no " + subclass.getFileName()
                + ": the container defines it at each start");
    }

    /** The directory this module's classes are compiled into, where the processors write their resources. */
    private static Path compiledOutput() throws URISyntaxException {
        return Path.of(TraceTestService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}

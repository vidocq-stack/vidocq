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
package io.vidocq.runtime.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The JDK tools the image goals run inside the Maven JVM, where a parallel build ({@code -T}) runs goals at once. */
class JdkToolsTest {

    private static final int BUILDS = 6;

    private static final PrintStream NOWHERE = new PrintStream(OutputStream.nullOutputStream());

    /** jlink of JDK 25 is not reentrant: of six images linked at once in one JVM, five failed with rc=1. */
    @Test
    void imagesLinkedAtOnceInOneJvmAreAllLinked(@TempDir Path dir) throws Exception {
        List<Integer> codes = atOnce(i -> JdkTools.run("jlink", NOWHERE, NOWHERE,
                "--add-modules", "java.base", "--output", dir.resolve("image" + i).toString()));
        assertEquals(List.of(0, 0, 0, 0, 0, 0), codes);
    }

    @Test
    void aToolMissingFromTheJdkIsAnError() {
        IOException missing = assertThrows(IOException.class,
                () -> JdkTools.run("no-such-tool", NOWHERE, NOWHERE));
        assertEquals("no-such-tool is not available in this JDK", missing.getMessage());
    }

    private interface Build {
        int run(int index) throws IOException;
    }

    /** Runs {@value #BUILDS} builds released together, as the threads of a parallel build would. */
    private static List<Integer> atOnce(Build build) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(BUILDS)) {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < BUILDS; i++) {
                int index = i;
                runs.add(threads.submit(() -> {
                    start.await();
                    return build.run(index);
                }));
            }
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> run : runs) {
                codes.add(run.get());
            }
            return codes;
        }
    }
}

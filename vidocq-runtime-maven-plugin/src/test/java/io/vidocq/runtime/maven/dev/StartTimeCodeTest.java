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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartTimeCodeTest {

    private static final StartTimeDeclarations KEEP = new StartTimeDeclarations(Set.of("app.Keep"), Set.of(),
            Set.of(), Set.of(), Set.of(), Set.of(), List.of("test"));

    @Test
    void theRootsAndWhatTheyCallButNotTheLambdasTheyCreate(@TempDir Path dir) {
        Path classes = HotSwapFixtures.compile(dir, "app", Map.of(
                "app/Keep.java", """
                        package app;
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                        @interface Keep {}
                        """,
                "app/Kept.java", """
                        package app;
                        @Keep class Kept {
                            static final int X = Integer.parseInt("1");
                            Runnable r;
                            Kept() { setup(); Step s = new Fast(); s.run(); r = () -> later(); }
                            private void setup() {}
                            private void later() {}
                            void perRequest() {}
                        }
                        """,
                "app/Step.java", "package app; abstract class Step { abstract void run(); }",
                "app/Fast.java", "package app; class Fast extends Step { void run() {} }",
                "app/Slow.java", "package app; class Slow extends Step { void run() {} }",
                "app/Other.java", "package app; class Other extends Step { Other() {} void run() {} }"));
        var snapshot = ClassesSnapshot.take(List.of(classes), ClassesSnapshot.EMPTY);
        var warnings = new ArrayList<String>();

        var startTime = StartTimeCode.of(snapshot, KEEP, SupertypeIndex.of(snapshot, List.of(), warnings::add));

        assertEquals(new TreeSet<>(Set.of(
                "app.Kept#<clinit>()V", "app.Kept#<init>()V", "app.Kept#setup()V",
                "app.Step#<init>()V", "app.Step#run()V",
                "app.Fast#<init>()V", "app.Fast#run()V", "app.Slow#run()V", "app.Other#run()V")),
                new TreeSet<>(startTime));
        assertEquals(List.of(), warnings);
    }
}

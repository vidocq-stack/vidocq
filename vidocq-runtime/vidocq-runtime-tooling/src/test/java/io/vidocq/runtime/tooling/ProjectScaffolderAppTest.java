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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScaffolderAppTest {

    private static final String RUNTIME = "1.2.3";
    private static final Reporter SILENT = new RecordingReporter();

    private static Path scaffold(Path dir, Set<String> extensions, boolean main) throws IOException {
        ProjectScaffolder.scaffold(
                new ProjectSpec("demo", "com.acme", "com.acme.demo", extensions, null, main), dir, RUNTIME, SILENT);
        return dir.resolve("demo");
    }

    @Test
    void defaultScaffoldHasNoApplicationMainClass(@TempDir Path dir) throws IOException {
        Path project = scaffold(dir, Set.of(), false);

        // The runtime's own main boots the application from vidocq.mainModule: vidocq:dev,
        // vidocq:run and the vidocq:package launchers need no application main class.
        assertFalse(Files.exists(project.resolve("src/main/java/com/acme/demo/DemoApp.java")));
        String pom = Files.readString(project.resolve("pom.xml"));
        assertTrue(pom.contains("<vidocq.mainModule>com.acme.demo</vidocq.mainModule>"), pom);
        assertFalse(pom.contains("vidocq.mainClass"), pom);
        assertFalse(pom.contains("<mainClass>"), pom);
        String moduleInfo = Files.readString(project.resolve("src/main/java/module-info.java"));
        assertFalse(moduleInfo.contains("requires io.vidocq.runtime.core"),
                "only the trampoline uses the runtime's API, module-info was:\n" + moduleInfo);
    }

    @Test
    void mainOptionWritesAVidocqRunTrampoline(@TempDir Path dir) throws IOException {
        Path project = scaffold(dir, Set.of(), true);
        String app = Files.readString(project.resolve("src/main/java/com/acme/demo/DemoApp.java"));

        // Vidocq.run re-resolves the application into the Vauban layer, which promotes
        // META-INF/services to provides; a main that boots in place leaves the generated
        // _VaubanComponents unseen in a jlink image and falls back to reflection.
        assertTrue(app.contains("@VidocqMain"), app);
        assertTrue(app.contains("Vidocq.run(args);"), app);
        assertFalse(app.contains("VidocqBootstrap"), app);
        // The Javadoc says when a trampoline is worth having.
        assertTrue(app.contains("VidocqApp"), app);
        // vidocq:jlink and vidocq:jpackage link a main-less application: not a reason for one.
        assertFalse(app.contains("vidocq:jlink"), app);

        String pom = Files.readString(project.resolve("pom.xml"));
        assertTrue(pom.contains("<vidocq.mainClass>com.acme.demo.DemoApp</vidocq.mainClass>"), pom);
        // BUG-20261024-07: vidocq:package reads vidocq.mainClass and adds the module itself (BUG-20260710-02, #198).
        assertFalse(pom.contains("<mainClass>"), pom);
        assertFalse(pom.contains("BUG-2026"), pom);
        String moduleInfo = Files.readString(project.resolve("src/main/java/module-info.java"));
        assertTrue(moduleInfo.contains("requires io.vidocq.runtime.core;"), moduleInfo);
    }

    /** BUG-20261024-09: a name that ends in "app" gave DemoAppApp. */
    @Test
    void theClassNameEndsInAppOnce() {
        assertEquals("DemoApp", ProjectScaffolder.appClassName("demo-app"));
        assertEquals("MyCoolApp", ProjectScaffolder.appClassName("my-cool-app"));
        assertEquals("MyApiApp", ProjectScaffolder.appClassName("my_api"));
        assertEquals("ShopApp", ProjectScaffolder.appClassName("shop"));
        assertEquals("App", ProjectScaffolder.appClassName("app"));
        assertEquals("WebappApp", ProjectScaffolder.appClassName("webapp"));
    }

    @Test
    void mainOptionNamesTheClassOfADemoAppDemoApp(@TempDir Path dir) throws IOException {
        ProjectScaffolder.scaffold(new ProjectSpec("demo-app", "com.acme", "com.acme.demo.app", Set.of(), null, true),
                dir, RUNTIME, SILENT);

        assertTrue(Files.isRegularFile(dir.resolve("demo-app/src/main/java/com/acme/demo/app/DemoApp.java")));
        assertTrue(Files.readString(dir.resolve("demo-app/pom.xml"))
                .contains("<vidocq.mainClass>com.acme.demo.app.DemoApp</vidocq.mainClass>"));
    }

    /**
     * BUG-20261024-08: without a wrapper, {@code vidocq doctor} warned on the project {@code create} had just made,
     * and Maven was whatever the PATH held. The scaffold writes the wrapper of this repository, so the Maven version
     * moves with the ecosystem's.
     */
    @Test
    void theScaffoldWritesTheEcosystemsMavenWrapper(@TempDir Path dir) throws IOException {
        Path project = scaffold(dir, Set.of(), false);

        Path repository = Path.of("..");
        for (String file : new String[] {"mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties"}) {
            assertEquals(Files.readString(repository.resolve(file)), Files.readString(project.resolve(file)), file);
        }
        if (project.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertTrue(Files.isExecutable(project.resolve("mvnw")), "mvnw must be executable");
        }
        DoctorContext context = DoctorFacts.gather(project, null, () -> DoctorFacts.Extensions.found(0));
        assertEquals(Diagnostic.Status.OK, Diagnostics.run(context).stream()
                .filter(d -> d.name().equals("Maven wrapper")).findFirst().orElseThrow().status());
    }

    @Test
    void restScaffoldWithoutMainWritesAResourceInTheOpenedPackage(@TempDir Path dir) throws IOException {
        Path project = scaffold(dir, Set.of("cassini-rest"), false);

        // An opens of a package without classes compiles with a warning, then the JVM
        // refuses the module: "Package com.acme.demo not found in module".
        String resource = Files.readString(project.resolve("src/main/java/com/acme/demo/HelloResource.java"));
        assertTrue(resource.contains("@Path(\"/hello\")"), resource);
        assertTrue(resource.contains("@GET"), resource);
        String moduleInfo = Files.readString(project.resolve("src/main/java/module-info.java"));
        assertTrue(moduleInfo.contains("opens com.acme.demo;"), moduleInfo);
    }

    @Test
    void noOpensForAPackageTheScaffoldLeavesEmpty(@TempDir Path dir) throws IOException {
        Path project = scaffold(dir, Set.of("mansart-data"), false);

        String moduleInfo = Files.readString(project.resolve("src/main/java/module-info.java"));
        assertFalse(moduleInfo.contains("opens com.acme.demo"),
                "the JVM refuses to open a package with no class, module-info was:\n" + moduleInfo);
        assertTrue(moduleInfo.contains("requires io.vidocq.runtime.extensions.jakartaee.web.mansart.data;"),
                "the extension's requires stay, module-info was:\n" + moduleInfo);
    }

    @Test
    void mainOptionKeepsTheOpensItsClassAnchors(@TempDir Path dir) throws IOException {
        Path project = scaffold(dir, Set.of("mansart-data"), true);

        String moduleInfo = Files.readString(project.resolve("src/main/java/module-info.java"));
        assertTrue(moduleInfo.contains("opens com.acme.demo;"), moduleInfo);
    }
}

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

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every MicroProfile extension requires the Config extension (Vidocq/vidocq#829): without Ravel there is no MicroProfile
 * Config implementation, and Heisenberg, Cyrano, Grimm and the others silently ignore their keys. So each one depends
 * on {@code vidocq-runtime-ravel-config-extension}, {@code requires} its module, has its codegen bundle depend on
 * Ravel's, and names {@code config-ravel} in {@code requires-extensions}, for {@code vidocq extension add} to declare
 * it with its own codegen bundle.
 */
class MicroProfileExtensionsRequireConfigTest {

    private static final Path EXTENSIONS = Path.of("..", "vidocq-runtime-extensions");
    private static final String CONFIG = "config-ravel";
    private static final String RAVEL = "vidocq-runtime-ravel-config-extension";
    private static final String RAVEL_CODEGEN = RAVEL + "-codegen";
    private static final Pattern REQUIRES_RAVEL =
            Pattern.compile("\\brequires\\s+(transitive\\s+)?io\\.vidocq\\.runtime\\.extensions\\.microprofile\\.ravel\\s*;");
    /** Serves the Swagger UI's static files: it reads no key, and goes with Grimm, which brings Ravel. */
    private static final Set<String> READ_NO_CONFIGURATION = Set.of("openapi-ui-grimm");
    private static final String DEPENDENCY_ARTIFACT_ID = "project/dependencies/dependency/artifactId";

    @Test
    void everyMicroProfileExtensionRequiresTheConfigExtension() throws IOException {
        Properties catalog = new Properties();
        catalog.load(new StringReader(ExtensionCatalogTest.render(EXTENSIONS)));
        List<String> checked = new ArrayList<>();
        for (String id : microProfileExtensions(catalog)) {
            assertTrue(csv(catalog.getProperty(id + ".requires-extensions")).contains(CONFIG),
                    id + ": its descriptor's requires-extensions must name " + CONFIG);

            Path module = module(catalog.getProperty(id + ".artifactId"));
            assertTrue(dependencies(module.resolve("pom.xml")).contains(RAVEL), id + ": its pom must depend on " + RAVEL);
            assertTrue(REQUIRES_RAVEL.matcher(uncommented(module.resolve("src/main/java/module-info.java"))).find(),
                    id + ": its module-info must require the Ravel extension's module");

            String codegen = catalog.getProperty(id + ".codegen");
            if (codegen != null) {
                assertTrue(dependencies(module(codegen).resolve("pom.xml")).contains(RAVEL_CODEGEN),
                        codegen + " must depend on " + RAVEL_CODEGEN);
            }
            checked.add(id);
        }
        assertEquals(List.of("fault-tolerance-heisenberg", "health-knock", "jwt-cervantes", "metrics-dirac",
                "openapi-grimm", "rest-client-cyrano", "telemetry-humboldt"), checked);
    }

    private static List<String> microProfileExtensions(Properties catalog) {
        return catalog.stringPropertyNames().stream()
                .filter(key -> key.endsWith(".family") && "microprofile".equals(catalog.getProperty(key)))
                .map(key -> key.substring(0, key.length() - ".family".length()))
                .filter(id -> !id.equals(CONFIG) && !READ_NO_CONFIGURATION.contains(id))
                .sorted()
                .toList();
    }

    private static Path module(String artifactId) throws IOException {
        try (var modules = Files.walk(EXTENSIONS, 2)) {
            List<Path> found = modules.filter(p -> p.getFileName().toString().equals(artifactId)).toList();
            assertFalse(found.isEmpty(), artifactId + " is no module of " + EXTENSIONS);
            return found.getFirst();
        }
    }

    private static List<String> csv(String value) {
        return value == null ? List.of() : List.of(value.split("\\s*,\\s*"));
    }

    private static String uncommented(Path source) throws IOException {
        return Files.readString(source).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /** The artifactIds of the project's own dependencies, not of its dependency management nor of its plugins. */
    private static List<String> dependencies(Path pom) throws IOException {
        return ExtensionCatalogTest.texts(pom, DEPENDENCY_ARTIFACT_ID::equals).stream().map(Map.Entry::getValue).toList();
    }
}

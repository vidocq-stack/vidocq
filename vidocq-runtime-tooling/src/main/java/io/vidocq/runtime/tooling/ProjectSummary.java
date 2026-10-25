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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What {@code vidocq info} says about the project in a directory, read from its files without running Maven
 * (Vidocq/vidocq#353): its main module, the runtime version it builds against, the catalog extensions it declares
 * and its active profile. A value the project does not give is {@code null}; the extensions its dependencies bring
 * transitively are {@code vidocq extension list}'s business.
 *
 * @param mainModule     the module {@code src/main/java/module-info.java} declares, else the pom's
 *                       {@code vidocq.mainModule} property
 * @param runtimeVersion the version of its {@code vidocq-runtime-parent}, else of the {@code vidocq-runtime-bom} it
 *                       imports
 * @param extensions     the ids of the catalog extensions among its direct dependencies, sorted
 * @param profile        {@code VIDOCQ_PROFILE}, else the {@code vidocq.profile} of its {@code vidocq.properties}
 * @param profileSource  where {@code profile} comes from: {@code VIDOCQ_PROFILE} or {@code vidocq.properties}
 */
public record ProjectSummary(String mainModule, String runtimeVersion, List<String> extensions, String profile,
                             String profileSource) {

    /** The environment variable MicroProfile Config maps to {@value #PROFILE_KEY}. */
    public static final String PROFILE_VARIABLE = "VIDOCQ_PROFILE";

    private static final String PROFILE_KEY = "vidocq.profile";
    private static final String PROPERTIES = "vidocq.properties";

    private static final Pattern MODULE = Pattern.compile("\\bmodule\\s+([\\w.]+)\\s*\\{");
    private static final Pattern MAIN_MODULE = Pattern.compile("<vidocq\\.mainModule>\\s*([^<\\s]+)\\s*<");
    private static final Pattern BOM = Pattern.compile(
            "<artifactId>vidocq-runtime-bom</artifactId>\\s*<version>\\s*([^<\\s]+)\\s*</version>");

    public ProjectSummary {
        extensions = List.copyOf(extensions);
    }

    /**
     * The summary of the project in {@code projectDir}, which holds a {@code pom.xml}.
     *
     * @param env reads an environment variable, {@code null} when it is not set
     */
    public static ProjectSummary read(Path projectDir, UnaryOperator<String> env) throws IOException {
        String pom = Files.readString(projectDir.resolve("pom.xml"));
        List<ExtensionCoordinate> dependencies = PomDependencies.parse(pom);
        List<String> extensions = KnownExtensions.catalog().stream()
                .filter(e -> dependencies.contains(e.coordinate()))
                .map(RegistryEntry::id)
                .toList();

        String profile = env.apply(PROFILE_VARIABLE);
        String profileSource = PROFILE_VARIABLE;
        if (profile == null || profile.isBlank()) {
            Optional<Path> config = ConfigFile.locate(projectDir, Files::isRegularFile);
            profile = config.isPresent()
                    ? PropertiesText.get(Files.readString(config.get()), PROFILE_KEY).orElse(null)
                    : null;
            profileSource = profile == null ? null : PROPERTIES;
        }
        return new ProjectSummary(mainModule(projectDir, pom),
                PomDependencies.runtimeVersion(pom).orElseGet(() -> first(BOM, pom)),
                extensions, profile, profileSource);
    }

    private static String mainModule(Path projectDir, String pom) throws IOException {
        Path moduleInfo = projectDir.resolve("src/main/java/module-info.java");
        if (Files.isRegularFile(moduleInfo)) {
            String source = Files.readString(moduleInfo)
                    .replaceAll("(?s)/\\*.*?\\*/", "")
                    .replaceAll("//[^\\n]*", "");
            String declared = first(MODULE, source);
            if (declared != null) {
                return declared;
            }
        }
        return first(MAIN_MODULE, pom);
    }

    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}

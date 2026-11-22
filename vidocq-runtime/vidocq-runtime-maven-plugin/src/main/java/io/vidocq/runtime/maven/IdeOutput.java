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

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The folder an IDE compiles a project into, and when.
 *
 * <p>An IDE that reads a project through m2e (the Java extension of VS Code, Eclipse) compiles it with its own
 * compiler into the directories Maven gives it, which are the build's: {@code target/classes} then holds classes that
 * are not woven and misses what {@code vidocq:generate} and the processors write, and a later {@code package} without
 * {@code clean} ships them. So when a project is read by an IDE its build directory is {@value #DIRECTORY}, next to
 * {@code target}: what the IDE writes is its own, what Maven builds is Maven's, and {@code clean} leaves the IDE's
 * folder alone.</p>
 *
 * <p>"Read by an IDE" is the property {@value #M2E_VERSION}, which m2e sets on the Maven session it reads a project
 * with. A Maven build does not carry it, on the command line, in CI or started from the IDE, and neither does
 * IntelliJ IDEA's import: nothing changes for them. {@code vidocq-parent} gives the modules of the monorepo the same
 * rule with its profile {@value #PROFILE}.</p>
 *
 * <p>Compiled for Java 17 with the participant that calls it ({@link CodegenPathsParticipant}).</p>
 */
public final class IdeOutput {

    /** The property m2e sets on the session it reads a project with; its value does not matter. */
    public static final String M2E_VERSION = "m2e.version";
    /** The project property that leaves a project where Maven builds it: {@code true}, in any case. */
    public static final String SKIP = "vidocq.ide.output.skip";
    /** The build directory of a project an IDE reads, in the project's folder. */
    public static final String DIRECTORY = "target-ide";
    /** The profile of {@code vidocq-parent} that applies the same rule to the modules of the monorepo. */
    public static final String PROFILE = "ide-output";

    private static final Logger LOG = LoggerFactory.getLogger(IdeOutput.class);
    private static final String PACKAGING_POM = "pom";
    private static final String IGNORE_FILE = ".gitignore";
    /** Everything in the folder, the file itself included. */
    private static final String IGNORE_ALL = "*\n";

    private IdeOutput() {
    }

    /** Whether the session is an IDE's read of the project: either set of properties holds {@value #M2E_VERSION}. */
    static boolean readByAnIde(Properties userProperties, Properties systemProperties) {
        return holds(userProperties) || holds(systemProperties);
    }

    private static boolean holds(Properties properties) {
        return properties != null && properties.getProperty(M2E_VERSION) != null;
    }

    /**
     * Moves the build directory, the output directory and the test output directory of {@code project} under
     * {@value #DIRECTORY} in its folder, whatever its pom says of them, and makes the folder ignore itself.
     *
     * @return false, and nothing done, for a project that was not read from a file, of packaging {@code pom}, or
     *         that sets {@value #SKIP}
     */
    static boolean move(MavenProject project) {
        File basedir = project.getBasedir();
        if (basedir == null || PACKAGING_POM.equals(project.getPackaging()) || skipped(project)) {
            return false;
        }
        File directory = new File(basedir.getAbsoluteFile(), DIRECTORY);
        Build build = project.getBuild();
        build.setDirectory(directory.getPath());
        build.setOutputDirectory(new File(directory, "classes").getPath());
        build.setTestOutputDirectory(new File(directory, "test-classes").getPath());
        ignore(directory.toPath());
        return true;
    }

    private static boolean skipped(MavenProject project) {
        String skip = project.getProperties().getProperty(SKIP);
        return skip != null && Boolean.parseBoolean(skip.trim());
    }

    /**
     * Writes {@code directory/.gitignore}, which ignores the whole folder, when it is missing: a project gets the
     * folder without a line in its own ignore file. Never throws: a file that cannot be written leaves the folder
     * to the project's ignore rules, and must not fail the IDE's import.
     */
    static void ignore(Path directory) {
        Path file = directory.resolve(IGNORE_FILE);
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(directory);
                Files.write(file, IGNORE_ALL.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("[vidocq] cannot write {}: the folder is left to the project's ignore rules", file, e);
        }
    }
}

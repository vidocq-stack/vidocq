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
package io.vidocq.runtime.maven.idea;

import io.vidocq.runtime.maven.idea.IdeaApplications.Discovery;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * What the goals that write IDE files share, {@code vidocq:vscode} first (Vidocq/vidocq#339): from the reactor root,
 * they find the applications as {@code vidocq:idea} does and write one folder of IDE files for them.
 *
 * <p>Each file starts with the IDE's marker line: a file that does is rewritten, and removed when the goal no longer
 * writes it; any other file is left alone, with what to add to it. With {@code check}, the goal writes nothing and
 * fails when a file is missing, out of date or no longer written.</p>
 */
abstract class IdeFilesMojo extends AbstractMojo {

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    private List<MavenProject> reactorProjects;

    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    private MojoExecution mojoExecution;

    /** Only check that the files are present and up to date; write nothing. */
    private boolean check;

    /** The goal, as in {@code vidocq:<goal>}. */
    abstract String goal();

    /** The IDE, for messages. */
    abstract String ide();

    /** The folder of the files, under the reactor root. */
    abstract String folder();

    /** The files to write for {@code applications}, by name in {@link #folder()}. */
    abstract Map<String, String> files(List<IdeaApplication> applications, Path directory);

    /** The first line of every file the goal writes. */
    abstract String marker();

    /** Says what the files cannot do themselves, once they are written. */
    void after() {
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (mojoExecution != null && mojoExecution.getSource() != MojoExecution.Source.CLI) {
            throw new MojoFailureException(prefix() + "this goal runs from the command line only (\"mvn vidocq:"
                    + goal() + "\"), but execution '" + mojoExecution.getExecutionId() + "' binds it to the build."
                    + " Remove that <execution> from the pom.");
        }
        List<MavenProject> allProjects = session.getAllProjects() != null ? session.getAllProjects() : reactorProjects;
        run(reactorProjects, allProjects, session.getUserProperties(), Path.of(session.getExecutionRootDirectory()));
    }

    void run(List<MavenProject> projects, List<MavenProject> allProjects, Properties userProperties, Path executionRoot)
            throws MojoExecutionException, MojoFailureException {
        Path directory = reactorRoot(executionRoot);
        List<IdeaApplication> applications = applications(projects, allProjects, directory, userProperties);
        if (applications.isEmpty()) {
            getLog().warn(prefix() + "no Vidocq application in this build: a module needs vidocq-runtime-maven-plugin"
                    + " in its <build><plugins> and a vidocq.mainModule property.");
            return;
        }
        Map<String, String> files = files(applications, directory);
        Path folder = directory.resolve(folder());
        List<String> drift = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            sync(folder.resolve(file.getKey()), file.getValue(), drift);
        }
        for (Path stale : stale(folder, files)) {
            remove(stale, drift);
        }
        if (!drift.isEmpty()) {
            drift.forEach(line -> getLog().error(prefix() + line));
            throw new MojoFailureException(prefix() + drift.size() + " file(s) missing or out of date: run mvn"
                    + " vidocq:" + goal() + ".");
        }
        if (check) {
            getLog().info(prefix() + folder() + "/ is up to date (" + files.size() + " files).");
        } else {
            after();
        }
    }

    /** {@code executionRoot}, which must be the reactor root: the IDE opens it, and the folder belongs there. */
    private Path reactorRoot(Path executionRoot) throws MojoExecutionException, MojoFailureException {
        Path directory;
        Path aggregator;
        try {
            directory = executionRoot.toRealPath();
            aggregator = ProjectDirectories.aggregatorOf(directory);
            if (aggregator == null) {
                return directory;
            }
            Path reactorRoot = ProjectDirectories.reactorRootOf(directory);
            throw new MojoFailureException(prefix() + directory + " is a module of " + aggregator + ". " + ide()
                    + " usually opens the reactor root, where " + folder() + "/ belongs: run vidocq:" + goal()
                    + " from " + reactorRoot + ".");
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read " + executionRoot, e);
        }
    }

    /** The applications of the build, found as {@code vidocq:idea} finds them; fails on a configuration error. */
    private List<IdeaApplication> applications(List<MavenProject> projects, List<MavenProject> allProjects,
                                               Path directory, Properties userProperties)
            throws MojoFailureException {
        Discovery discovery = IdeaApplications.discover(projects, allProjects, directory, userProperties);
        discovery.diagnostics().forEach(d -> {
            switch (d.level()) {
                case WARN -> getLog().warn(d.message());
                case INFO -> getLog().info(d.message());
                default -> getLog().debug(d.message());
            }
        });
        if (!discovery.errors().isEmpty()) {
            discovery.errors().forEach(getLog()::error);
            throw new MojoFailureException(prefix() + discovery.errors().size() + " configuration error(s); nothing"
                    + " was written.");
        }
        return discovery.applications();
    }

    /** Writes {@code content} to {@code path} unless it is up to date or not ours; with {@code check}, notes it. */
    private void sync(Path path, String content, List<String> drift) throws MojoExecutionException {
        String shown = folder() + "/" + path.getFileName();
        String existing = read(path);
        if (existing != null && !existing.startsWith(marker())) {
            getLog().warn(prefix() + shown + " was not written by vidocq:" + goal() + ": left alone. Add to it:\n"
                    + content.substring(marker().length()));
        } else if (content.equals(existing)) {
            getLog().debug(prefix() + shown + " is up to date.");
        } else if (check) {
            drift.add(shown + (existing == null ? " is missing" : " is out of date"));
        } else {
            write(path, content);
            getLog().info(prefix() + (existing == null ? "created " : "updated ") + shown);
        }
    }

    /** Deletes a file of ours this run no longer writes; with {@code check}, notes it. */
    private void remove(Path stale, List<String> drift) throws MojoExecutionException {
        String shown = folder() + "/" + stale.getFileName();
        if (check) {
            drift.add(shown + " is no longer written");
        } else {
            delete(stale);
            getLog().info(prefix() + "removed " + shown);
        }
    }

    final String prefix() {
        return "Vidocq " + goal() + ": ";
    }

    /** The files of {@code folder} that start with the marker but that this run does not write. */
    private List<Path> stale(Path folder, Map<String, String> files) throws MojoExecutionException {
        List<Path> stale = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return stale;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                String content = Files.isRegularFile(entry) ? read(entry) : null;
                if (content != null && content.startsWith(marker())
                        && !files.containsKey(entry.getFileName().toString())) {
                    stale.add(entry);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot list " + folder, e);
        }
        stale.sort(null);
        return stale;
    }

    private static String read(Path file) throws MojoExecutionException {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read " + file, e);
        }
    }

    private static void write(Path file, String content) throws MojoExecutionException {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write " + file, e);
        }
    }

    private static void delete(Path file) throws MojoExecutionException {
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot delete " + file, e);
        }
    }

    void setCheck(boolean check) {
        this.check = check;
    }

    void setMojoExecution(MojoExecution mojoExecution) {
        this.mojoExecution = mojoExecution;
    }
}

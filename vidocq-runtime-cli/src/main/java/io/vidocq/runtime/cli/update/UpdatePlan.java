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
package io.vidocq.runtime.cli.update;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides what {@code vidocq update} installs, from the Maven metadata of the CLI
 * distribution — the same sources as {@code install.sh}.
 *
 * <ul>
 *   <li>A release moves to the latest release of Maven Central, when it is newer.</li>
 *   <li>A SNAPSHOT moves to the highest SNAPSHOT line of the snapshot repository; on its
 *       own line, to the latest build when it is not the one installed. The installed build
 *       is the marker {@link CliInstaller} records. A CLI installed otherwise has none: it
 *       takes the published build only when that one was deployed after it was built, so
 *       a CLI built locally from newer sources is never downgraded.</li>
 * </ul>
 *
 * <p>Pure: metadata is read through the injected {@code fetch}.</p>
 */
public final class UpdatePlan {

    private static final String ARTIFACT = "vidocq-runtime-cli";
    private static final String SNAPSHOT = "-SNAPSHOT";
    private static final Pattern RELEASE = Pattern.compile("<release>\\s*([^<\\s]+)\\s*</release>");
    private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");
    private static final Pattern CLI_ZIP = Pattern.compile(
            "<snapshotVersion>\\s*<classifier>cli</classifier>\\s*<extension>zip</extension>"
                    + "\\s*<value>\\s*([^<\\s]+)\\s*</value>(?:\\s*<updated>\\s*(\\d{14})\\s*</updated>)?");

    /** What to do. */
    public sealed interface Decision {}

    /** Nothing newer than {@code current}. */
    public record UpToDate(String current) implements Decision {}

    /**
     * Install {@code version} from {@code zipUrl}; {@code build} is the timestamped
     * SNAPSHOT build to record, empty for a release.
     */
    public record Update(String version, String zipUrl, Optional<String> build) implements Decision {}

    /** The repository could not answer. */
    public record Unavailable(String reason) implements Decision {}

    private UpdatePlan() {}

    /**
     * @param current        the running CLI version
     * @param installedBuild the SNAPSHOT build recorded at install time, if any
     * @param builtAt        when the running CLI was built ({@code yyyy-MM-dd'T'HH:mm:ss'Z'}),
     *                       blank when unknown
     * @param fetch          reads a URL, empty when it cannot
     * @param central        base URL of the CLI artifact on Maven Central
     * @param snapshots      base URL of the CLI artifact in the snapshot repository
     */
    public static Decision plan(String current, Optional<String> installedBuild, String builtAt,
                                Function<String, Optional<String>> fetch,
                                String central, String snapshots) {
        return current.endsWith(SNAPSHOT)
                ? planSnapshot(current, installedBuild, builtAt, fetch, snapshots)
                : planRelease(current, fetch, central);
    }

    private static Decision planRelease(String current, Function<String, Optional<String>> fetch,
                                        String central) {
        String url = central + "/maven-metadata.xml";
        Optional<String> latest = fetch.apply(url).flatMap(xml -> first(RELEASE, xml));
        if (latest.isEmpty()) {
            return new Unavailable("no release found at " + url);
        }
        String version = latest.get();
        if (compare(version, current) <= 0) {
            return new UpToDate(current);
        }
        return new Update(version, central + "/" + version + "/" + ARTIFACT + "-" + version + "-cli.zip",
                Optional.empty());
    }

    private static Decision planSnapshot(String current, Optional<String> installedBuild, String builtAt,
                                         Function<String, Optional<String>> fetch, String snapshots) {
        String url = snapshots + "/maven-metadata.xml";
        Optional<String> highest = fetch.apply(url).flatMap(xml -> VERSION.matcher(xml).results()
                .map(m -> m.group(1))
                .filter(v -> v.endsWith(SNAPSHOT))
                .max(Comparator.comparing(v -> v, UpdatePlan::compare)));
        if (highest.isEmpty()) {
            return new Unavailable("no SNAPSHOT found at " + url);
        }
        String line = compare(highest.get(), current) > 0 ? highest.get() : current;
        String lineUrl = snapshots + "/" + line + "/maven-metadata.xml";
        Optional<Matcher> zip = fetch.apply(lineUrl).map(CLI_ZIP::matcher).filter(Matcher::find);
        if (zip.isEmpty()) {
            return new Unavailable("no " + line + " CLI distribution at " + lineUrl);
        }
        Optional<String> build = Optional.of(zip.get().group(1));
        if (line.equals(current)) {
            if (installedBuild.equals(build)) {
                return new UpToDate(current + " (" + build.get() + ")");
            }
            String deployed = zip.get().group(2);
            if (installedBuild.isEmpty() && deployed != null && !builtAt.isBlank()
                    && deployed.compareTo(builtAt.replaceAll("\\D", "")) <= 0) {
                return new UpToDate(current + " (built " + builtAt + ", after the published "
                        + build.get() + ")");
            }
        }
        return new Update(line, snapshots + "/" + line + "/" + ARTIFACT + "-" + build.get() + "-cli.zip", build);
    }

    /** Compares the numeric {@code major.minor.patch} parts; a qualifier is ignored. */
    static int compare(String a, String b) {
        int[] x = numbers(a);
        int[] y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(i < x.length ? x[i] : 0, i < y.length ? y[i] : 0);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int[] numbers(String version) {
        String base = version.split("-", 2)[0];
        return Arrays.stream(base.split("\\."))
                .mapToInt(part -> part.chars().allMatch(Character::isDigit) && !part.isEmpty()
                        ? Integer.parseInt(part) : 0)
                .toArray();
    }

    private static Optional<String> first(Pattern pattern, String xml) {
        Matcher m = pattern.matcher(xml);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}

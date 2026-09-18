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
package io.vidocq.runtime.core.banner;

import io.vidocq.runtime.core.BannerMode;
import io.vidocq.runtime.core.console.ConsoleSupport;
import io.vidocq.runtime.spi.report.LaunchMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.zip.ZipEntry;

/** Builders and captures shared by the banner tests. */
final class BannerTestSupport {

    /** The ANSI escape character. */
    static final String ESC = String.valueOf((char) 27);
    /** First line of the built-in art (the top of the "V" and of the "i" dot). */
    static final String ART_FIRST_LINE = "__     ___     _";
    /** Number of lines of the built-in art. */
    static final int ART_LINES = 6;
    static final String IDEA_AGENT = "-javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=51234:/x";

    private BannerTestSupport() {}

    static Function<String, Optional<String>> config(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key));
    }

    /** A launch whose mode was not resolved: the context line has no launch segment. */
    static StartupBanner.Launch launch(BannerMode override) {
        return launch(override, null, null);
    }

    static StartupBanner.Launch launch(BannerMode override, LaunchModeResolver.Resolution mode, DebugAgent debug) {
        return new StartupBanner.Launch(override, false, false, mode, debug, null, null);
    }

    /** A mode read from a deliberate signal, as {@link LaunchModeResolver} returns it. */
    static LaunchModeResolver.Resolution mode(LaunchMode mode, String reason) {
        return new LaunchModeResolver.Resolution(mode, reason, true, mode == LaunchMode.DEV);
    }

    static LaunchModeResolver.Resolution dev(String reason) {
        return mode(LaunchMode.DEV, reason);
    }

    /** A dev read from the shape of a build tree: no developer is known to be watching. */
    static LaunchModeResolver.Resolution devFromBuildTree(String reason) {
        return new LaunchModeResolver.Resolution(LaunchMode.DEV, reason, true, false);
    }

    /** The {@code prod} no signal proves, which never shows without its reason. */
    static LaunchModeResolver.Resolution prodByAbsence() {
        return new LaunchModeResolver.Resolution(LaunchMode.PROD, LaunchModeResolver.NO_SIGNAL, false, false);
    }

    static ConsoleSupport console(boolean terminal, String noColor, String term, String os, String... arguments) {
        return new ConsoleSupport(noColor, term, os, terminal, List.of(arguments));
    }

    /** Standard output redirected to a pipe or a file, on Linux. */
    static ConsoleSupport pipe() {
        return console(false, null, null, "Linux");
    }

    static BuildInfo jar(String version, String commit, Boolean dirty, String builtAt, String committedAt,
                         String fileDatedAt) {
        return new BuildInfo("io.vidocq.runtime.core", version, commit, dirty, instant(builtAt), instant(committedAt),
                URI.create("file:///home/dev/.m2/repository/io/vidocq/runtime/vidocq-runtime-core.jar"), false,
                instant(fileDatedAt), "vidocq-runtime-core");
    }

    static BuildInfo directory(String version, String path) {
        return new BuildInfo("io.vidocq.runtime.core", version, "9beafc47", false, instant("2026-09-17T14:02:11Z"),
                null, Path.of(path).toUri(), true, null, null);
    }

    static StartupIdentity identity(BuildInfo vidocq, String vendor, LaunchModeResolver.Resolution launch,
                                    String appName, String appVersion) {
        return identity(vidocq, vendor, launch, null, appName, appVersion);
    }

    static StartupIdentity identity(BuildInfo vidocq, String vendor, LaunchModeResolver.Resolution launch,
                                    String debug, String appName, String appVersion) {
        return new StartupIdentity(vidocq, "25+36-LTS", vendor, launch, debug, appName, appVersion, List.of());
    }

    static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    /** A jar holding {@code entries} (name to content). */
    static Path writeJar(Path jar, Map<String, byte[]> entries) throws IOException {
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** What one call printed on its standard output. */
    static final class Out {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        String text() {
            stream.flush();
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    /** The records of one logger, while open; the banner's unless another name is given. */
    static final class Records implements AutoCloseable {
        private final Logger logger;
        private final List<LogRecord> records = new ArrayList<>();
        private final Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                synchronized (records) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };

        Records() {
            this(StartupBanner.class.getName());
        }

        Records(String loggerName) {
            logger = Logger.getLogger(loggerName);
            handler.setLevel(Level.ALL);
            logger.addHandler(handler);
        }

        /** The formatted messages at {@code level}. */
        List<String> messages(Level level) {
            SimpleFormatter formatter = new SimpleFormatter();
            synchronized (records) {
                return records.stream().filter(r -> r.getLevel() == level).map(formatter::formatMessage).toList();
            }
        }

        /** The INFO messages but the bricks line, which depends on the jars of this build. */
        List<String> identityRecords() {
            return messages(Level.INFO).stream().filter(m -> !m.startsWith("Vidocq bricks: ")).toList();
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }
}

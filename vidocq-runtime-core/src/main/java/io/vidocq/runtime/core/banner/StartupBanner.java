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
import io.vidocq.runtime.core.console.Ansi;
import io.vidocq.runtime.core.console.ConsoleSupport;
import io.vidocq.runtime.spi.config.VidocqConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The startup banner: the Vidocq art, then the Vidocq identity and the context of the launch.
 *
 * <pre>
 *  Vidocq 0.4.0-SNAPSHOT (9beafc47+dirty, built 2026-09-17T14:02:11Z)
 *  Java 25+36-LTS | dev (IntelliJ agent) | debug *:5005 | mcp-time-server 0.1.0-SNAPSHOT
 * </pre>
 *
 * <p>{@link #showOnce} runs in {@code VidocqBootstrap.configure()}, right after the configuration is
 * loaded. It prints at most once per JVM (the dev reload loop configures again) and never fails the
 * boot. What it prints is decided by {@value #MODE_KEY} (see {@link BannerMode}): someone watching
 * (a terminal, a dev launch) gets the art on standard output; everything else gets one INFO line,
 * and no art ever reaches the logs unless {@code log} is chosen.
 */
public final class StartupBanner {

    /** Configuration key of the {@link BannerMode}: {@code auto}, {@code console}, {@code log} or {@code off}. */
    public static final String MODE_KEY = "vidocq.banner.mode";
    /** Configuration key of a custom banner: {@code classpath:<resource>}, {@code file:<path>} or {@code <path>}. */
    public static final String LOCATION_KEY = "vidocq.banner.location";
    /** The custom banner picked up without configuration, through the context class loader. */
    static final String CUSTOM_RESOURCE = "vidocq-banner.txt";
    /** The built-in art, a raw text resource next to this class (a text block or a properties file would mangle its backslashes). */
    static final String ART_RESOURCE = "art.txt";
    /** Present on the class path or module path of a JUnit Platform test run (Surefire, an IDE). */
    static final String TEST_RUNTIME_PROBE = "org/junit/platform/launcher/Launcher.class";
    /** The width of the generated lines. */
    static final int WIDTH = 80;

    private static final System.Logger LOG = System.getLogger(StartupBanner.class.getName());
    private static final AtomicBoolean SHOWN = new AtomicBoolean();
    private static volatile StartupIdentity emitted;

    /** What the banner finally does. */
    enum Output {
        /** The art and the two lines on standard output. */
        CONSOLE,
        /** The art and the two lines in one INFO record. */
        LOG,
        /** One INFO line: identity and context. */
        IDENTITY_ONLY,
        /** Nothing. */
        NONE
    }

    /**
     * How the runtime was launched.
     *
     * @param override           the mode forced by {@code VidocqBootstrap.banner(BannerMode)}, or {@code null}
     * @param embeddedDeployment {@code configure(List)}: Arquillian, the TCK
     * @param testRuntime        a JUnit Platform launcher is present
     * @param launchMode         the resolved {@link LaunchMode} and the signal it was read from
     * @param debug              the JDWP agent of this JVM, or {@code null} when it runs without one
     * @param appModule          the application module, or {@code null}
     * @param appLayer           the application layer installed by Vidocq, or {@code null}
     */
    public record Launch(BannerMode override, boolean embeddedDeployment, boolean testRuntime,
                         LaunchModeResolver.Resolution launchMode, DebugAgent debug, Module appModule,
                         ModuleLayer appLayer) {

        /** Whether the launch was resolved as a development one, which shows the art without a terminal. */
        public boolean devLaunch() {
            return launchMode != null && launchMode.mode() == LaunchMode.DEV;
        }
    }

    private StartupBanner() {}

    /** The launch of this JVM: its {@link LaunchModeResolver resolved mode} and its debugger. */
    public static Launch launch(VidocqConfig config, BannerMode override, boolean embeddedDeployment,
                                Module appModule, ModuleLayer appLayer) {
        ConsoleSupport console = ConsoleSupport.current();
        LaunchModeResolver.Resolution launchMode = LaunchModeResolver.resolve(
                LaunchModeResolver.Inputs.current(config::getValue, appModule, console));
        return new Launch(override, embeddedDeployment, testRuntime(), launchMode,
                DebugAgent.detect(console.jvmArguments()).orElse(null), appModule, appLayer);
    }

    /** Shows the banner unless this JVM already did; never throws. */
    public static void showOnce(VidocqConfig config, Supplier<Launch> launch) {
        showOnce(config::getValue, launch, ConsoleSupport::current, System.out);
    }

    /** The identity the banner emitted in this JVM, empty when it printed nothing. */
    public static Optional<StartupIdentity> emittedIdentity() {
        return Optional.ofNullable(emitted);
    }

    static boolean showOnce(Function<String, Optional<String>> config, Supplier<Launch> launch,
                            Supplier<ConsoleSupport> console, PrintStream out) {
        if (!SHOWN.compareAndSet(false, true)) {
            return false;
        }
        try {
            show(config, launch.get(), console.get(), out);
        } catch (RuntimeException | LinkageError e) {
            LOG.log(System.Logger.Level.DEBUG, "Startup banner skipped", e);
        }
        return true;
    }

    /** Lets the next {@link #showOnce} print again. */
    static void reset() {
        SHOWN.set(false);
        emitted = null;
    }

    static void show(Function<String, Optional<String>> config, Launch launch, ConsoleSupport console,
                     PrintStream out) {
        BannerMode mode = launch.override() != null ? launch.override() : mode(config);
        Output output = decide(mode, launch, console.terminal());
        if (output == Output.NONE) {
            return;
        }
        StartupIdentity identity = StartupIdentity.collect(launch);
        switch (output) {
            case IDENTITY_ONLY -> LOG.log(System.Logger.Level.INFO,
                    identity.identityLine() + " | " + identity.contextLine());
            // the formatter prefixes the first line only: a leading newline keeps the art in column 0
            case LOG -> LOG.log(System.Logger.Level.INFO, "\n" + render(identity, config, false).stripTrailing());
            case CONSOLE -> {
                boolean colors = console.colors(config.apply(ConsoleSupport.COLOR_KEY)
                        .flatMap(ConsoleSupport.ColorMode::parse).orElse(ConsoleSupport.ColorMode.AUTO));
                out.print(render(identity, config, colors));
                out.flush();
            }
            case NONE -> {
                // handled above
            }
        }
        emitted = identity;
        Optional<String> bricks = identity.bricksLine();
        if (bricks.isPresent()) {
            LOG.log(System.Logger.Level.INFO, bricks.get());
        }
    }

    /**
     * An explicit mode wins. {@code auto} gives one line to embedded deployments and test runtimes,
     * the art to a terminal or a dev launch, and one line to everything else (containers, CI, pipes).
     */
    static Output decide(BannerMode mode, Launch launch, boolean terminal) {
        return switch (mode) {
            case OFF -> Output.NONE;
            case CONSOLE -> Output.CONSOLE;
            case LOG -> Output.LOG;
            case AUTO -> launch.embeddedDeployment() || launch.testRuntime() ? Output.IDENTITY_ONLY
                    : terminal || launch.devLaunch() ? Output.CONSOLE
                    : Output.IDENTITY_ONLY;
        };
    }

    static boolean testRuntime() {
        return ClassLoader.getSystemResource(TEST_RUNTIME_PROBE) != null;
    }

    /** {@value #MODE_KEY}, {@code auto} when unset; an unknown value logs one warning and means {@code auto}. */
    static BannerMode mode(Function<String, Optional<String>> config) {
        Optional<String> raw = config.apply(MODE_KEY).filter(v -> !v.isBlank());
        if (raw.isEmpty()) {
            return BannerMode.AUTO;
        }
        try {
            return BannerMode.valueOf(raw.get().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            LOG.log(System.Logger.Level.WARNING, "Configuration key ''{0}'' has an unknown value ''{1}''"
                    + " (expected auto, console, log or off); using auto", MODE_KEY, raw.get());
            return BannerMode.AUTO;
        }
    }

    // ----------------------------------------------------------------------------------- rendering

    /** The art, then the identity and context lines unless a custom banner placed the identity itself. */
    static String render(StartupIdentity identity, Function<String, Optional<String>> config, boolean colors) {
        Optional<String> custom = customBanner(config);
        if (custom.isPresent()) {
            String template = custom.get();
            String text = normalize(BannerTemplate.resolve(template, key -> placeholder(key, identity, colors, config)));
            return BannerTemplate.references(template, "vidocq.identity") ? text : text + identityLines(identity, colors);
        }
        return builtinArt(colors) + identityLines(identity, colors);
    }

    /** The identity and context lines, each at most {@value #WIDTH} columns before styling. */
    static String identityLines(StartupIdentity identity, boolean colors) {
        String line = identity.identityLine(WIDTH - 1);
        int split = line.indexOf(" (");
        String head = split < 0 ? line : line.substring(0, split);
        String details = split < 0 ? "" : line.substring(split + 1);
        return " " + style(Ansi.BOLD, head, colors) + (details.isEmpty() ? "" : " " + style(Ansi.YELLOW, details, colors))
                + "\n " + style(Ansi.FAINT, identity.contextLine(WIDTH - 1), colors) + "\n";
    }

    /** The built-in art, one line per line of the resource, in cyan when colours are on. */
    static String builtinArt(boolean colors) {
        StringBuilder sb = new StringBuilder();
        for (String line : artLines(readResource(StartupBanner.class, ART_RESOURCE).orElse(""))) {
            sb.append(style(Ansi.CYAN, line, colors)).append('\n');
        }
        return sb.toString();
    }

    /** The lines of an art text, whatever its line endings, without trailing blank lines. */
    static List<String> artLines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\\R", -1)));
        while (!lines.isEmpty() && lines.getLast().isBlank()) {
            lines.removeLast();
        }
        return lines;
    }

    /** {@code line}, cut to {@code max} columns with {@code ...}. */
    static String fit(String line, int max) {
        if (line.length() <= max) {
            return line;
        }
        return max <= 3 ? line.substring(0, max) : line.substring(0, max - 3) + "...";
    }

    private static String style(String code, String text, boolean colors) {
        return colors && !text.isEmpty() ? Ansi.wrap(code, text) : text;
    }

    /** Line endings as {@code \n}, and a final one. */
    private static String normalize(String text) {
        String lines = text.replaceAll("\\R", "\n");
        return lines.isEmpty() || lines.endsWith("\n") ? lines : lines + "\n";
    }

    // -------------------------------------------------------------------------------- custom banner

    static String placeholder(String key, StartupIdentity identity, boolean colors,
                              Function<String, Optional<String>> config) {
        if (key.startsWith("ansi.")) {
            String code = ansi(key.substring("ansi.".length()));
            return code == null ? null : colors ? code : "";
        }
        return switch (key) {
            case "vidocq.version" -> identity.vidocq().version();
            case "vidocq.build" -> identity.vidocq().details();
            case "vidocq.identity" -> identity.identityLine();
            case "java.version" -> identity.java();
            case "app.name" -> identity.appName();
            case "app.version" -> identity.appVersion();
            case "vidocq.launch" -> identity.launch() == null ? null : identity.launch().text();
            case "vidocq.debug" -> identity.debug();
            default -> {
                try {
                    yield config.apply(key).orElse(null);
                } catch (RuntimeException unreadable) {
                    yield null;
                }
            }
        };
    }

    private static String ansi(String name) {
        return switch (name) {
            case "reset" -> Ansi.RESET;
            case "bold" -> Ansi.BOLD;
            case "faint" -> Ansi.FAINT;
            case "red" -> Ansi.RED;
            case "green" -> Ansi.GREEN;
            case "yellow" -> Ansi.YELLOW;
            case "blue" -> Ansi.BLUE;
            case "magenta" -> Ansi.MAGENTA;
            case "cyan" -> Ansi.CYAN;
            default -> null;
        };
    }

    /**
     * The custom banner: {@value #LOCATION_KEY} when set (a missing file, or a value that is no file
     * location, logs a warning and gives the built-in banner), otherwise a {@value #CUSTOM_RESOURCE}
     * resource of the application.
     */
    static Optional<String> customBanner(Function<String, Optional<String>> config) {
        Optional<String> location = config.apply(LOCATION_KEY).map(String::strip).filter(l -> !l.isEmpty());
        if (location.isEmpty()) {
            return readResource(contextLoader(), CUSTOM_RESOURCE);
        }
        String value = location.get();
        Optional<String> text;
        if (value.startsWith("classpath:")) {
            String resource = value.substring("classpath:".length());
            text = readResource(contextLoader(), resource.startsWith("/") ? resource.substring(1) : resource);
        } else {
            Path path;
            try {
                path = filePath(value);
            } catch (RuntimeException invalid) {
                // an unencoded space in a file:// URI, an authority, a NUL character: the art and the identity still print
                LOG.log(System.Logger.Level.WARNING, "Banner {0}={1} is not a file location ({2}); using the built-in banner",
                        LOCATION_KEY, value, invalid.getMessage());
                return Optional.empty();
            }
            text = readFile(path);
        }
        if (text.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "Banner {0}={1} not found; using the built-in banner",
                    LOCATION_KEY, value);
        }
        return text;
    }

    /**
     * The file of a {@code file://} URI, of {@code file:<path>} or of a plain path.
     *
     * @throws IllegalArgumentException when the value names no file: a URI that does not parse or has an
     *                                  authority, a query or a fragment, or a path the file system rejects
     *                                  ({@link java.nio.file.InvalidPathException})
     */
    static Path filePath(String value) {
        if (value.startsWith("file://")) {
            return Path.of(URI.create(value));
        }
        return Path.of(value.startsWith("file:") ? value.substring("file:".length()) : value);
    }

    private static ClassLoader contextLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : StartupBanner.class.getClassLoader();
    }

    private static Optional<String> readResource(Object owner, String name) {
        try (InputStream in = owner instanceof Class<?> type ? type.getResourceAsStream(name)
                : owner instanceof ClassLoader loader ? loader.getResourceAsStream(name)
                : ClassLoader.getSystemResourceAsStream(name)) {
            return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static Optional<String> readFile(Path path) {
        try {
            return Files.isRegularFile(path) ? Optional.of(Files.readString(path, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}

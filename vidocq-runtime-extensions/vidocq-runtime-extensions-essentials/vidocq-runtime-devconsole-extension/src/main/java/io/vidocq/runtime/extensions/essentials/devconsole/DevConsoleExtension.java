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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.StaticFileHandler;
import io.vidocq.runtime.extensions.essentials.chappe.ChappeListener;
import io.vidocq.runtime.extensions.essentials.chappe.ChappeMountPoint;
import io.vidocq.runtime.extensions.essentials.chappe.ListenerOptions;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * The dev console: a page, on a listener of its own, that shows the startup report of the running boot and the live
 * values of the {@linkplain io.vidocq.runtime.spi.devconsole.DevConsolePanel dev console panels}, and, in a dev
 * launch only, runs the actions they offer.
 *
 * <h2>When it is on</h2>
 * <p>{@value DevConsoleSettings#ENABLED_KEY} is {@code auto} by default: on in a dev launch, off otherwise;
 * {@code true} and {@code false} force it. It listens on {@value DevConsoleSettings#HOST_KEY}, {@code 127.0.0.1} by
 * default, and {@value DevConsoleSettings#PORT_KEY}, {@code 8888} by default, {@code 0} for a free one. An invalid
 * value is reported ({@code VIDOCQ-DEVC-003}) and its default used.
 *
 * <h2>Lifecycle</h2>
 * <p>Priority {@value #PRIORITY}: after {@code chappe-engine} (100), which installs the mount point, and before
 * {@code chappe-bootstrap} (10,000), which starts the servers. Everything happens in {@link #onStart}, the first phase
 * that knows the launch mode: the console declares its listener, {@value #LISTENER}, with
 * {@link ChappeMountPoint#declareListener(ChappeListener, ListenerOptions)}, and mounts itself at its root. Chappe then
 * starts it with the other listeners and hands the console the address it bound ({@link #bound}), from which the
 * console prints its URL, {@code Vidocq dev console: http://127.0.0.1:8888/}, on the first boot of the JVM and again
 * only when it changes. A configured port that is taken does not stop the application: the console listens on a free
 * port, and says so loudly, with a boxed WARNING, a {@code VIDOCQ-DEVC-004} anomaly and a banner on its page. For
 * port {@code 0}, a dev reload asks again for the port the previous boot bound, so that an open tab keeps working.
 * Every dev reload creates a new console, a new listener and a new boot id; {@link #onStop} runs once the servers
 * are down.
 *
 * <h2>Its section of the report</h2>
 * <p>The console is also the contributor of the report's {@value #ID} section: the URL, or why it is off, and its
 * anomalies, {@code VIDOCQ-DEVC-001} when it is on outside a dev launch, {@code VIDOCQ-DEVC-002} when it listens on
 * an address that is not a loopback one, {@code VIDOCQ-DEVC-003} and {@code VIDOCQ-DEVC-004}. That section is shown
 * with the report on the page, not as a panel.
 *
 * <h2>What it serves</h2>
 * <p>See {@link ConsoleHandler}: {@code GET /api/snapshot}, the {@link Snapshot} of the boot, and the page, from the
 * resources under {@value #PAGE_RESOURCES}; nothing else, and only to a request that names the console's own
 * address. In a dev launch, {@code POST /api/action/<panel>/<action>} too, behind an origin check and a per-boot
 * token ({@link ConsoleActions}, ADR 0001). The page, {@code index.html}, {@code console.css} and the ES module
 * {@code console.js}, loads nothing from another site: it polls the snapshot and draws the report first, then one tab per panel, the console's own
 * {@linkplain ConfigPanel configuration}, {@linkplain CdiPanel CDI} and {@linkplain JvmPanel JVM} panels last.
 *
 * <h2>The history of its curves</h2>
 * <p>Five minutes of every measure is kept by the console, in a {@link PanelHistory} filled by a thread of its own,
 * {@value #TICKER}, once a {@value Snapshot#POLL_MILLIS} milliseconds for the life of the boot. It used to be kept
 * by the page, which lost it whenever the browser stopped the timers of a hidden tab — so the curve had a hole
 * exactly over the minutes someone had left the console to go and cause something. The page now asks for the points
 * it does not have, {@code ?since=}, and draws a complete curve when it comes back.
 */
public final class DevConsoleExtension implements VidocqExtension, StartupReportContributor {

    /** The name of the extension and the id of its section of the report. */
    static final String ID = "devconsole";
    /** The logger of the console's own records: its URL, a taken port, a panel that fails. */
    static final String LOGGER_NAME = "io.vidocq.devconsole";
    /** The Chappe listener the console declares for itself. */
    static final String LISTENER = "dev";
    /** After {@code chappe-engine} (100), before {@code chappe-bootstrap} (10,000). */
    static final int PRIORITY = 9000;
    /** Where the page's files are: not a package, so nothing to open. */
    static final String PAGE_RESOURCES = "META-INF/resources/devconsole";

    /** The console is on outside a dev launch. */
    static final String ON_OUTSIDE_DEV = "VIDOCQ-DEVC-001";
    /** The console listens on an address that is not a loopback one. */
    static final String NOT_LOOPBACK = "VIDOCQ-DEVC-002";
    /** A {@code vidocq.devconsole.*} value the console does not accept. */
    static final String INVALID_VALUE = "VIDOCQ-DEVC-003";
    /** The configured port was taken: the console listens on another one. */
    static final String PORT_TAKEN = "VIDOCQ-DEVC-004";

    /** How long stopping the console's server waits for a request in flight: never Chappe's 30 seconds. */
    private static final Duration GRACE_PERIOD = Duration.ofSeconds(1);
    /** The thread that fills the history, one tick a second, for the life of a boot. */
    static final String TICKER = "vidocq-devconsole-history";
    /** How long {@link #onStop} waits for that thread: a tick is microseconds, this is only for a pathological one. */
    private static final Duration TICKER_STOP = Duration.ofSeconds(2);
    /** The build identity file of this module, for its version on the class path. */
    private static final String BUILD_INFO =
            "/META-INF/vidocq/build-info/vidocq-runtime-devconsole-extension.properties";
    /** A URL {@link ChappeListener#httpUrl} writes: a loopback name or an address, a port, nothing after the slash. */
    private static final Pattern PRINTABLE_URL = Pattern.compile(
            "http://(localhost|[0-9]{1,3}(\\.[0-9]{1,3}){3}|\\[[0-9a-f:.]+(%25[0-9A-Za-z._~-]+)?]):[0-9]{1,5}/");
    private static final System.Logger LOG = System.getLogger(LOGGER_NAME);
    private static final String VIDOCQ_VERSION = version();

    private final ConsoleMemory memory;
    private final LongSupplier clock;

    private volatile DevConsoleSettings settings;
    private volatile Snapshot snapshot;
    private volatile InetSocketAddress boundAddress;
    private volatile String url;
    private volatile String notStarted;
    private volatile Thread ticker;

    /** The console Vidocq loads as a service, remembering what it printed across the dev reloads of this JVM. */
    public DevConsoleExtension() {
        this(ConsoleMemory.JVM);
    }

    /** @param memory what outlives a dev reload */
    DevConsoleExtension(ConsoleMemory memory) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.clock = System::currentTimeMillis;
    }

    @Override
    public String name() {
        return ID;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    /**
     * The console's three keys, each named: a mistyped {@code vidocq.devconsole.*} key, which nothing reads, is
     * reported by the key audit ({@code VIDOCQ-CFG-003}), with these three as the known keys.
     */
    @Override
    public Set<String> configKeys() {
        return Set.of(DevConsoleSettings.ENABLED_KEY, DevConsoleSettings.PORT_KEY, DevConsoleSettings.HOST_KEY);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Dev console";
    }

    /**
     * Resolves the settings and, when the console is on, declares its listener and mounts the snapshot and the page on
     * it. Never fails the boot: a listener that cannot be declared leaves the console off, with a WARNING.
     */
    @Override
    public void onStart(ExtensionContext context) {
        DevConsoleSettings resolved = DevConsoleSettings.resolve(context.config(), context.launchMode());
        settings = resolved;
        if (!resolved.on()) {
            return;
        }
        int port = resolved.port() == 0 ? memory.portForAnyPort() : resolved.port();
        // Actions exist in a dev launch only (ADR 0001): anywhere else, no token, and no panel's actions() called.
        ConsoleActions actions = resolved.launchMode() == LaunchMode.DEV
                ? new ConsoleActions(ConsoleActions.newToken(), clock, ConsoleActions.TIME_LIMIT)
                : null;
        Snapshot boot = new Snapshot(HexFormat.of().toHexDigits(RandomGenerator.getDefault().nextLong()),
                VIDOCQ_VERSION, context.startupReport(), ownPanels(context, resolved.launchMode()), clock, actions);
        Handler page = StaticFileHandler.builder()
                .addClasspath(DevConsoleExtension.class.getClassLoader(), PAGE_RESOURCES)
                .indexFile("index.html")
                .cacheControl("no-cache")
                .build();
        snapshot = boot;
        try {
            ChappeMountPoint mountPoint = ChappeMountPoint.instance();
            mountPoint.declareListener(ChappeListener.http(LISTENER, resolved.host(), port),
                    new ListenerOptions(true, true, GRACE_PERIOD, this::bound));
            mountPoint.mount(LISTENER, "", new ConsoleHandler(new HostGuard(resolved.host()), this::boundPort, boot,
                    page));
            ticker = startTicking(boot);
        } catch (RuntimeException failed) {
            snapshot = null;
            notStarted = String.valueOf(failed.getMessage());
            LOG.log(System.Logger.Level.WARNING, "Vidocq dev console not started: " + notStarted);
        }
    }

    /**
     * Starts the thread that fills the history: one {@link Snapshot#tick()} every {@value Snapshot#POLL_MILLIS}
     * milliseconds, for the life of this boot.
     *
     * <p>It runs whether or not a page is open, and that is the point. The only signal the server has for "nobody is
     * watching" is that no snapshot was asked for — which is exactly what a hidden tab causes, so stopping on it
     * would empty the history during the absence the history exists to cover.
     *
     * <p>A daemon thread: it must never hold a JVM open. A tick that throws costs itself and the loop goes on, since
     * the next one may well succeed — a panel reading a bean that was not there yet, for instance.
     */
    private static Thread startTicking(Snapshot boot) {
        Thread thread = Thread.ofPlatform().name(TICKER).daemon(true).unstarted(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    boot.tick();
                } catch (RuntimeException | LinkageError costsThisTick) {
                    LOG.log(System.Logger.Level.DEBUG, "Dev console history tick failed", costsThisTick);
                }
                try {
                    Thread.sleep(Snapshot.POLL_MILLIS);
                } catch (InterruptedException stopping) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        thread.start();
        return thread;
    }

    /**
     * The console's own panels, shown after the contributed ones from the first poll, the boot facts of each written
     * once per boot: the {@linkplain ConfigPanel configuration}, the {@linkplain CdiPanel CDI container}, then the
     * {@linkplain JvmPanel JVM}, last. The report's own panel, {@code startup}, is the snapshot's {@code startup}
     * member, which the page shows first.
     */
    private static List<PanelEntry> ownPanels(ExtensionContext context, LaunchMode mode) {
        return List.of(PanelEntry.builtIn(configPanel(context, mode), mode),
                PanelEntry.builtIn(cdiPanel(context), mode), PanelEntry.builtIn(new JvmPanel(), mode));
    }

    /** The {@code config} panel, the configuration read now; one that says it has none when reading it fails. */
    private static ConfigPanel configPanel(ExtensionContext context, LaunchMode mode) {
        try {
            return ConfigPanel.of(context.config(), mode, context.startupReport());
        } catch (RuntimeException | LinkageError failed) {
            // the failure's class only: its message may quote a configured value
            LOG.log(System.Logger.Level.DEBUG, "Dev console panel 'config' could not read the configuration: "
                    + failed.getClass().getName());
            return new ConfigPanel(null, context.startupReport());
        }
    }

    /** The {@code cdi} panel, its metadata read now; one that says it has none when reading it fails. */
    private static CdiPanel cdiPanel(ExtensionContext context) {
        try {
            return CdiPanel.of(context.container());
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "Dev console panel 'cdi' could not read the container", failed);
            return new CdiPanel(null);
        }
    }

    /**
     * Called by Chappe on the boot thread once the console's listener is bound: records where, prints the URL when
     * it is new, and tells loudly when the configured port was taken.
     *
     * @param address the address the listener bound
     */
    void bound(InetSocketAddress address) {
        DevConsoleSettings resolved = settings;
        String bound = ChappeListener.httpUrl(address);
        boundAddress = address;
        url = bound;
        memory.bound(address.getPort());
        Snapshot boot = snapshot;
        if (boot != null) {
            boot.bound(bound, resolved.port(), address.getPort());
        }
        if (memory.toPrint(bound)) {
            LOG.log(System.Logger.Level.INFO, "Vidocq dev console: " + bound);
        }
        if (portTaken(resolved, address)) {
            LOG.log(System.Logger.Level.WARNING, portTakenWarning(resolved.port(), address.getPort(), bound));
        }
    }

    /** The port the console listens on, {@code 0} until it is bound. */
    int boundPort() {
        InetSocketAddress address = boundAddress;
        return address == null ? 0 : address.getPort();
    }

    /**
     * Writes the console's section: its URL, or why it is off or not started, and its anomalies. Called by Vidocq
     * once every {@code onStart} ran, the console's listener bound.
     */
    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        DevConsoleSettings resolved = settings;
        if (resolved == null) {
            section.summary("not started");
            return;
        }
        for (String invalid : resolved.invalid()) {
            section.anomaly(INVALID_VALUE, invalid, null);
        }
        if (!resolved.on()) {
            section.summary(resolved.offReason());
            return;
        }
        InetSocketAddress address = boundAddress;
        section.row("enabled", resolved.enabled())
                .row("configured", hostAndPort(resolved.host(), resolved.port()));
        if (address == null) {
            section.summary("not started");
            if (notStarted != null) {
                section.row("reason", notStarted);
            }
            return;
        }
        String bound = url;
        section.summary(bound).listener(LISTENER, bound);
        if (resolved.launchMode() != LaunchMode.DEV) {
            section.anomaly(ON_OUTSIDE_DEV, "The dev console is on in a " + resolved.launchMode().label()
                    + " launch: it shows the startup report and the live values of this application on "
                    + hostAndPort(resolved.host(), address.getPort()),
                    "Remove " + DevConsoleSettings.ENABLED_KEY + "=true outside development");
        }
        if (!address.getAddress().isLoopbackAddress()) {
            section.anomaly(NOT_LOOPBACK, "The dev console listens on "
                    + hostAndPort(address.getAddress().getHostAddress(), address.getPort())
                    + ", which is not a loopback address: whoever reaches this machine can read the startup report "
                    + "and the live values of this application",
                    "Remove " + DevConsoleSettings.HOST_KEY + ", or set it to " + DevConsoleSettings.DEFAULT_HOST);
        }
        if (portTaken(resolved, address)) {
            section.anomaly(PORT_TAKEN, "The dev console's port " + resolved.port() + " is taken: it listens on port "
                    + address.getPort() + " instead, " + bound,
                    "Free port " + resolved.port() + ", or set " + DevConsoleSettings.PORT_KEY
                            + " to another port, 0 for any free one");
        }
    }

    /**
     * Forgets this boot: the server is already down, {@code chappe-bootstrap} stops before the console.
     *
     * <p>The ticker is stopped first, and waited for. A dev reload builds a new console on the same JVM, and a tick
     * still running would sample panels whose beans the previous boot has already dropped.
     */
    @Override
    public void onStop() {
        Thread ticking = ticker;
        ticker = null;
        if (ticking != null) {
            ticking.interrupt();
            try {
                ticking.join(TICKER_STOP.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        Snapshot boot = snapshot;
        if (boot != null) {
            boot.history().clear();
        }
        snapshot = null;
        boundAddress = null;
        url = null;
    }

    private static boolean portTaken(DevConsoleSettings resolved, InetSocketAddress address) {
        return resolved != null && resolved.port() != 0 && resolved.port() != address.getPort();
    }

    /**
     * The WARNING of a taken port: one line to find it in a log, then a box that holds numbers and the URL only, never
     * a string of the configuration, and the URL only when it has the shape {@link ChappeListener#httpUrl} gives it.
     */
    static String portTakenWarning(int configured, int bound, String url) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("DEV CONSOLE: port " + configured + " is taken");
        lines.add("It listens on port " + bound + " instead" + (PRINTABLE_URL.matcher(url).matches() ? ":" : "."));
        if (PRINTABLE_URL.matcher(url).matches()) {
            lines.add("");
            lines.add(url);
        }
        lines.add("");
        int width = 56;
        for (String line : lines) {
            width = Math.max(width, line.length() + 6);
        }
        String rule = "+" + "-".repeat(width - 2) + "+";
        StringBuilder warning = new StringBuilder("Vidocq dev console: port " + configured
                + " is taken, listening on port " + bound + " instead");
        warning.append('\n').append(rule);
        for (String line : lines) {
            warning.append('\n').append("|   ").append(line).append(" ".repeat(width - 5 - line.length())).append('|');
        }
        return warning.append('\n').append(rule).toString();
    }

    /** {@code host:port}, an IPv6 literal bracketed. */
    private static String hostAndPort(String host, int port) {
        boolean ipv6 = host.indexOf(':') >= 0 && !host.startsWith("[");
        return (ipv6 ? "[" + host + "]" : host) + ":" + port;
    }

    /** The version of Vidocq: this module's, from its descriptor on the module path, else from its build identity. */
    private static String version() {
        Module module = DevConsoleExtension.class.getModule();
        if (module.getDescriptor() != null && module.getDescriptor().rawVersion().isPresent()) {
            return module.getDescriptor().rawVersion().get();
        }
        try (InputStream in = DevConsoleExtension.class.getResourceAsStream(BUILD_INFO)) {
            if (in == null) {
                return null;
            }
            Properties info = new Properties();
            info.load(in);
            return info.getProperty("git.build.version");
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}

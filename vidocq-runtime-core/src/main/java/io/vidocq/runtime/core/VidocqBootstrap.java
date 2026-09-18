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
package io.vidocq.runtime.core;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.runtime.core.banner.LaunchModeResolver;
import io.vidocq.runtime.core.banner.StartupBanner;
import io.vidocq.runtime.core.config.ConfigKeyAudit;
import io.vidocq.runtime.core.config.VidocqConfigImpl;
import io.vidocq.runtime.core.console.ConsoleLogging;
import io.vidocq.runtime.core.console.ConsoleSupport;
import io.vidocq.runtime.core.report.StartupAnomalies;
import io.vidocq.runtime.core.report.VerbosityResolver;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.Verbosity;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Vidocq lifecycle orchestrator.
 * <p>
 * Startup sequence:
 * <ol>
 *   <li>Loading configuration</li>
 *   <li>Discovery of extensions (ServiceLoader, sorted by priority)</li>
 *   <li>{@code extension.configure(config)}</li>
 *   <li>Creation of the {@link VaubanContainerBuilder}, {@code extension.beforeStart(builder)}</li>
 *   <li>Build the {@link VaubanContainer} (CDI boot)</li>
 *   <li>{@code extension.onStart(context)}</li>
 *   <li>Registering the shutdown hook</li>
 *   <li>Block on {@link #awaitShutdown()}</li>
 * </ol>
 *
 * <p><b>Vidocq lifecycle orchestrator.</b></p>
 */
public final class VidocqBootstrap {

    private static final System.Logger LOG = System.getLogger(VidocqBootstrap.class.getName());

    /**
     * The configuration keys the core reads itself. The audit merges them with every extension's
     * {@link VidocqExtension#configKeys()}, so a typo under these namespaces is reported too.
     */
    static final Set<String> CORE_CONFIG_KEYS = Set.of(
            ConsoleLogging.LOG_CONSOLE_KEY,
            ConsoleSupport.COLOR_KEY,
            StartupBanner.MODE_KEY,
            StartupBanner.LOCATION_KEY,
            LaunchModeResolver.MODE_KEY,
            VerbosityResolver.KEY);

    /**
     * Whether a bootstrap of this JVM was configured already. The dev reload loop boots again in the same
     * JVM, and only its first boot gets the detailed startup report by default: this is the one piece of
     * report state that is the JVM's rather than a boot's.
     */
    private static final AtomicBoolean BOOTED = new AtomicBoolean();

    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private Thread shutdownHook;

    private VidocqConfig config;
    private VidocqConfiguration configuration;
    private List<VidocqExtension> extensions = List.of();
    private List<String> additionalBeanClassNames;
    private VaubanContainer container;
    /** The banner mode forced by the embedding code, or {@code null} when the configuration decides. */
    private BannerMode bannerOverride;
    /** {@link #configure(List)}: an Arquillian or TCK deployment, which gets the one-line banner. */
    private boolean embeddedDeployment;
    /** The launch of this boot, resolved by {@link #configure()}; {@code null} before. */
    private StartupBanner.Launch launch;
    /** How much the startup report of this boot shows, resolved by {@link #configure()}; nothing before. */
    private Verbosity verbosity = Verbosity.OFF;
    /**
     * Top-chrono taken during the construction of the bootstrap (= just after the entry
     * from {@code Vidocq.main()} via {@link #create()}). Comparable to {@code
     * StopWatch} started by {@code SpringApplication.run()} of Spring Boot.
     */
    private final long startTime = System.nanoTime();

    private VidocqBootstrap() {}

    /**
     * Creates a new bootstrap instance.
     */
    public static VidocqBootstrap create() {
        // Embedders that skip Vidocq.main get the console logging too, before the first log line.
        ConsoleLogging.installIfDefault();
        return new VidocqBootstrap();
    }

    /**
     * Forces the startup banner mode, whatever {@code vidocq.banner.mode} says: for code that embeds
     * Vidocq and owns its standard output, such as a tool whose output is data
     * ({@code VidocqBootstrap.create().banner(BannerMode.OFF).configure()}).
     *
     * @param mode the mode, or {@code null} to let the configuration decide
     * @return this bootstrap
     */
    public VidocqBootstrap banner(BannerMode mode) {
        this.bannerOverride = mode;
        return this;
    }

    /**
     * Phase 1: loads the configuration, resolves the launch mode and the level of the startup report, prints
     * the startup banner (once per JVM) and discovers the extensions.
     */
    public VidocqBootstrap configure() {
        // Universal-loader mode: embedders that skip Vidocq.main (the CLI boots
        // in-process) still get the application layer when -Dvidocq.app.path is set —
        // configuration sources below read through the loader installed here. No-op when
        // the property is absent or the layer is already in place.
        VidocqAppLayer.installIfConfigured();

        this.config = new VidocqConfigImpl();
        // vidocq.properties is visible from here: vidocq.log.console and vidocq.console.color.
        ConsoleLogging.applyConfiguration(config);
        // An invalid value of either key is reported once per boot, then read as auto.
        checkedSetting(config, LaunchModeResolver.MODE_KEY, LaunchModeResolver::isSetting, LaunchModeResolver.ACCEPTED);
        String report = checkedSetting(config, VerbosityResolver.KEY, VerbosityResolver::isSetting,
                VerbosityResolver.ACCEPTED);
        // Every boot resolves its own launch, the reloads of the dev loop included: the extensions read
        // it through ExtensionContext.launchMode(), and the configuration may have changed since.
        StartupBanner.Launch resolved = resolveLaunch(config);
        this.launch = resolved;
        this.verbosity = VerbosityResolver.resolve(report, launchMode(), embeddedDeployment,
                BOOTED.compareAndSet(false, true));
        // The banner needs the configuration (vidocq.banner.*) and comes before the first boot log line.
        StartupBanner.showOnce(config, () -> resolved);
        LOG.log(System.Logger.Level.INFO, "Vidocq - Configuration phase");
        this.configuration = new VidocqConfigurationImpl(config);
        this.extensions = ExtensionLoader.load();

        for (VidocqExtension ext : extensions) {
            ext.configure(configuration);
        }

        return this;
    }

    /**
     * Phase 1 (variant): configure with additional bean classes.
     * Used by the Arquillian container to inject deployment classes.
     */
    public VidocqBootstrap configure(java.util.List<String> additionalBeanClassNames) {
        this.embeddedDeployment = true;
        configure();
        this.additionalBeanClassNames = additionalBeanClassNames;
        return this;
    }

    /**
     * Phase 2: booting the CDI container and starting the extensions.
     */
    public VidocqBootstrap start() {
        LOG.log(System.Logger.Level.INFO, "Vidocq - Starting");

        // vauban#24 load-time weaving (IDE builds): must run before ANY extension code —
        // e.g. Cassini inspects @Path classes in beforeStart, and a bean class loaded
        // before the weaving agent is attached can no longer gain its (ProxyLink)
        // constructor. Vauban's container builder re-runs this as a no-op backstop.
        var weaving = io.vidocq.vauban.core.weaving.LoadTimeWeaving.prepare(
                Thread.currentThread().getContextClassLoader());
        if (weaving.failure() != null) {
            StartupAnomalies.warn(StartupAnomalies.WEAVING_FAILED, weaving.failure());
        }

        // Build CDI container
        VaubanContainerBuilder builder = VaubanContainer.builder()
                .scanClasspath();

        // Add extra bean classes (e.g. from Arquillian deployment)
        if (additionalBeanClassNames != null) {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            for (String className : additionalBeanClassNames) {
                try {
                    builder.addBeanClass(cl.loadClass(className));
                } catch (ClassNotFoundException | LinkageError e) {
                    // A bean class we cannot load or link is skipped, not fatal. This
                    // covers infrastructure classes bundled in a deployment's
                    // WEB-INF/lib (e.g. a TCK harness that packages a Maven resolver)
                    // whose optional transitive references are absent — they are not
                    // application beans, so dropping them must not abort the boot.
                    LOG.log(System.Logger.Level.WARNING,
                            "Skipping bean class that cannot be loaded/linked: " + className + " (" + e + ")");
                }
            }
        }

        for (VidocqExtension ext : extensions) {
            ext.beforeStart(builder);
        }

        this.container = builder.build();

        // Notify extensions
        ExtensionContext context = extensionContext();
        for (VidocqExtension ext : extensions) {
            LOG.log(System.Logger.Level.INFO, "Starting extension: {0}", ext.name());
            ext.onStart(context);
        }

        auditConfigKeys(config, extensions);

        // Shutdown hook
        shutdownHook = new Thread(this::shutdown, "vidocq-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        long elapsed = System.nanoTime() - startTime;
        long ms = elapsed / 1_000_000;
        long us = (elapsed / 1_000) % 1_000;
        long jvmUptime = ManagementFactory.getRuntimeMXBean().getUptime();
        LOG.log(System.Logger.Level.INFO,
                "Vidocq - Started in " + ms + "." + String.format("%03d", us)
                        + " ms (process running for " + jvmUptime + " ms)");
        return this;
    }

    /**
     * The launch of this boot: its {@link LaunchModeResolver resolved mode} and its debugger. Never
     * fails the boot: a launch that cannot be read has no mode, which the extensions see as
     * {@code prod}, and the banner shows what it knows.
     */
    private StartupBanner.Launch resolveLaunch(VidocqConfig config) {
        try {
            return StartupBanner.launch(config, bannerOverride, embeddedDeployment,
                    Vidocq.applicationModule(), VidocqAppLayer.installedLayer());
        } catch (RuntimeException | LinkageError e) {
            LOG.log(System.Logger.Level.DEBUG, "Launch mode not resolved", e);
            return new StartupBanner.Launch(bannerOverride, embeddedDeployment, false, null, null, null, null);
        }
    }

    /**
     * The value of {@code key}, stripped, or {@code null} when it is unset or cannot be read. A value
     * {@code valid} rejects is reported as {@code VIDOCQ-CFG-001}; the resolvers then read it as
     * {@code auto}.
     */
    private static String checkedSetting(VidocqConfig config, String key, Predicate<String> valid, String accepted) {
        String value;
        try {
            value = config.getValue(key).map(String::strip).filter(v -> !v.isEmpty()).orElse(null);
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (!valid.test(value)) {
            StartupAnomalies.warn(StartupAnomalies.INVALID_VALUE, StartupAnomalies.invalidValue(key, value, accepted));
        }
        return value;
    }

    /** Lets the next {@link #configure()} be the first boot of the JVM again. */
    static void forgetEarlierBoots() {
        BOOTED.set(false);
    }

    /**
     * How much the startup report of this boot shows: {@link Verbosity#OFF} before {@link #configure()}.
     * An embedded deployment shows nothing unless {@code vidocq.startup.report} asks for it.
     */
    Verbosity verbosity() {
        return verbosity;
    }

    /** The launch mode of this boot: {@code prod} before {@link #configure()}, or when it could not be read. */
    LaunchMode launchMode() {
        return launch == null || launch.launchMode() == null ? LaunchMode.PROD : launch.launchMode().mode();
    }

    /** The context every extension's {@code onStart} receives. */
    ExtensionContext extensionContext() {
        return new ExtensionContextImpl(container, configuration, config, launchMode());
    }

    /**
     * Warns about every configured {@code vidocq.*} key that nothing consumes, neither the core nor a
     * loaded extension ({@code VIDOCQ-CFG-003}).
     *
     * <p>Such a key is applied by nobody: the application silently keeps the default, and the
     * mistake stays invisible whenever the configured value happens to <em>be</em> the default —
     * how a documented {@code vidocq.http.port} sat inert in real applications for weeks
     * (Vidocq/chappe#7). Reporting is best-effort and never fails the boot: an audit that fails is a
     * warning of its own ({@code VIDOCQ-CFG-002}).
     */
    static void auditConfigKeys(VidocqConfig config, List<VidocqExtension> extensions) {
        try {
            Set<String> declared = declaredConfigKeys(extensions);
            for (String key : ConfigKeyAudit.unconsumedKeys(config.getPropertyNames(), declared)) {
                StartupAnomalies.warn(StartupAnomalies.UNREAD_KEY, ConfigKeyAudit.messageFor(key, declared));
            }
        } catch (RuntimeException e) {
            StartupAnomalies.warn(StartupAnomalies.AUDIT_FAILED, "Configuration key audit failed: " + e
                    + "; keys that nothing reads are not reported");
        }
    }

    /** The keys the core itself reads, audited like the extensions' {@code configKeys()}. */
    static Set<String> declaredConfigKeys(List<VidocqExtension> extensions) {
        Set<String> declared = new HashSet<>(CORE_CONFIG_KEYS);
        for (VidocqExtension ext : extensions) {
            declared.addAll(ext.configKeys());
        }
        return declared;
    }

    /**
     * Blocks the current thread until the server stops.
     */
    public void awaitShutdown() {
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown();
        }
    }

    /**
     * Waits up to {@code timeoutMillis} for {@link #shutdown()} to complete. Returns
     * {@code true} when the runtime is down — used by the dev-mode hot-reload loop to
     * poll for a reload signal while still honouring a normal shutdown.
     */
    public boolean awaitShutdown(long timeoutMillis) {
        try {
            return shutdownLatch.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    public void shutdown() {
        if (shutdownLatch.getCount() == 0) {
            return; // idempotent — the JVM hook and the reload loop may both call this
        }
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException jvmAlreadyShuttingDown) {
                // called FROM the hook — nothing to deregister
            }
        }
        LOG.log(System.Logger.Level.INFO, "Vidocq - Shutting down");

        // Stop extensions in reverse order
        List<VidocqExtension> reversed = new java.util.ArrayList<>(extensions);
        Collections.reverse(reversed);
        for (VidocqExtension ext : reversed) {
            try {
                ext.onStop();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.ERROR, "Error stopping extension: " + ext.name(), e);
            }
        }

        // Close CDI container
        if (container != null) {
            container.close();
        }

        shutdownLatch.countDown();
        LOG.log(System.Logger.Level.INFO, "Vidocq - Stopped");
    }
}

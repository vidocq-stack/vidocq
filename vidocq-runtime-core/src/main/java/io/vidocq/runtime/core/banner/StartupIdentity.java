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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Who is starting: the Vidocq version and build, the JVM, the launch and the application.
 * Computed once per JVM by the startup banner.
 *
 * @param vidocq      the build of {@code io.vidocq.runtime.core}, the module that carries the banner
 * @param javaVersion {@code Runtime.version()} of the running JVM
 * @param javaVendor  {@code java.vendor}, or {@code null}
 * @param launch      the resolved {@link LaunchMode} and its reason, or {@code null} when unknown
 * @param debug       the debugger segment ({@code debug *:5005}), or {@code null} without a JDWP agent
 * @param appName     the application's artifactId (packaged) or module name, or {@code null}
 * @param appVersion  the application's version, or {@code null}
 * @param bricks      the bricks worth a look, as {@code <brick> <describe()>}
 */
public record StartupIdentity(BuildInfo vidocq, String javaVersion, String javaVendor,
                              LaunchModeResolver.Resolution launch, String debug, String appName, String appVersion,
                              List<String> bricks) {

    /**
     * One known class per brick, for class-path launches, where no module tells the bricks apart.
     * Looked up without initialisation; an absent brick is skipped.
     */
    static final Map<String, List<String>> CLASS_PATH_ANCHORS = anchors();

    /** The shortest application name kept before the version when the context line is too wide. */
    private static final int MIN_NAME_COLUMNS = 8;

    public StartupIdentity {
        Objects.requireNonNull(vidocq, "vidocq");
        bricks = bricks == null ? List.of() : List.copyOf(bricks);
    }

    /** Collects the identity of this JVM for {@code launch}. */
    static StartupIdentity collect(StartupBanner.Launch launch) {
        BuildInfo vidocq = BuildInfo.ofClass(StartupBanner.class);
        String appName = null;
        String appVersion = null;
        Module app = launch.appModule();
        if (app != null && app.isNamed()) {
            BuildInfo info = BuildInfo.of(app);
            appName = info.artifactId() != null && !info.exploded() ? info.artifactId() : app.getName();
            appVersion = info.version();
        }
        return new StartupIdentity(vidocq, Runtime.version().toString(),
                BuildInfo.real(System.getProperty("java.vendor")), launch.launchMode(),
                launch.debug() == null ? null : launch.debug().segment(),
                appName, appVersion, bricks(vidocq, StartupBanner.class.getModule(), launch));
    }

    /** {@code Vidocq <version> (<details>)}. */
    public String identityLine() {
        return "Vidocq " + vidocq.describe();
    }

    /**
     * The identity line in at most {@code max} columns: a versioned directory of classes drops its
     * {@code last Maven build} label first (its {@code ?} stays), then the line is cut with {@code ...}.
     */
    public String identityLine(int max) {
        String line = identityLine();
        if (line.length() > max && vidocq.exploded()) {
            line = "Vidocq " + vidocq.describe(false);
        }
        return StartupBanner.fit(line, max);
    }

    /** {@code Java <version> (<vendor>)}. */
    public String java() {
        return java(true);
    }

    /**
     * {@code Java <version> [(<vendor>)] [| <mode> (<reason>)] [| debug <address>] [| <app> [<version>]]},
     * whatever its width.
     */
    public String contextLine() {
        return context(true, appName, true, true);
    }

    /**
     * The richest context line of at most {@code max} columns: the debugger goes first, then the
     * reason of the launch mode, then the JVM vendor, then the module segments of the application
     * name are abbreviated, then the name is cut with {@code ...}, always keeping the application
     * version; the whole line is cut last.
     *
     * <p>A {@code prod} that no signal proves has no short form: the whole segment goes with its
     * reason rather than claim, in one word, more than is known.
     */
    public String contextLine(int max) {
        String line = context(true, appName, true, true);
        if (line.length() <= max) {
            return line;
        }
        line = context(true, appName, true, false);
        if (line.length() <= max) {
            return line;
        }
        line = context(true, appName, false, false);
        if (line.length() <= max) {
            return line;
        }
        line = context(false, appName, false, false);
        if (line.length() <= max || appName == null) {
            return StartupBanner.fit(line, max);
        }
        String name = abbreviate(appName);
        line = context(false, name, false, false);
        if (line.length() <= max) {
            return line;
        }
        int kept = name.length() - (line.length() - max) - 3;
        if (kept >= MIN_NAME_COLUMNS) {
            return context(false, name.substring(0, kept) + "...", false, false);
        }
        return StartupBanner.fit(line, max);
    }

    /** {@code Vidocq bricks: <brick> <describe()>, ...}, when some brick is worth a look. */
    public Optional<String> bricksLine() {
        return bricks.isEmpty() ? Optional.empty() : Optional.of("Vidocq bricks: " + String.join(", ", bricks));
    }

    /** {@code io.vidocq.tools.lc4jcdi.mcptimeserver} becomes {@code i.v.t.l.mcptimeserver}. */
    static String abbreviate(String dotted) {
        String[] parts = dotted.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(parts[i].charAt(0));
            }
            sb.append('.');
        }
        return sb.append(parts[parts.length - 1]).toString();
    }

    /**
     * The bricks whose version differs from the runtime's, that were built from a modified tree, or
     * that are snapshots without a commit. Bricks have independent versions, so this is information,
     * not a warning.
     */
    static List<String> bricks(BuildInfo runtime, Module core, StartupBanner.Launch launch) {
        Map<String, BuildInfo> byBrick = new LinkedHashMap<>();
        if (core.isNamed()) {
            Set<ModuleLayer> layers = new LinkedHashSet<>();
            addWithParents(core.getLayer(), layers);
            addWithParents(ModuleLayer.boot(), layers);
            // Vidocq.run re-layers the application and the bricks it does not keep in the boot layer
            addWithParents(launch.appLayer(), layers);
            if (launch.appModule() != null) {
                addWithParents(launch.appModule().getLayer(), layers);
            }
            String appModule = launch.appModule() == null ? null : launch.appModule().getName();
            layers.stream()
                    .flatMap(layer -> layer.modules().stream())
                    .filter(m -> m.getName() != null && !m.getName().equals(appModule))
                    .sorted(Comparator.comparing(Module::getName))
                    .forEach(m -> {
                        String brick = brickOf(m.getName());
                        if (brick != null && !byBrick.containsKey(brick)) {
                            byBrick.put(brick, BuildInfo.of(m));
                        }
                    });
        } else {
            ClassLoader loader = StartupBanner.class.getClassLoader();
            CLASS_PATH_ANCHORS.forEach((brick, anchors) -> {
                for (String anchor : anchors) {
                    try {
                        byBrick.put(brick, BuildInfo.ofClass(Class.forName(anchor, false, loader)));
                        break;
                    } catch (ClassNotFoundException | LinkageError absent) {
                        // this brick is not on the class path
                    }
                }
            });
        }
        List<String> worthALook = new ArrayList<>();
        byBrick.forEach((brick, info) -> {
            if (worthALook(info, runtime)) {
                worthALook.add(brick + " " + info.describe());
            }
        });
        return worthALook;
    }

    static boolean worthALook(BuildInfo brick, BuildInfo runtime) {
        return !Objects.equals(brick.version(), runtime.version())
                || brick.isDirty()
                || (brick.snapshot() && brick.commit() == null);
    }

    /** {@code io.vidocq.<brick>.*} gives {@code <brick>}; the runtime itself and VidocqTools applications give {@code null}. */
    static String brickOf(String moduleName) {
        if (!moduleName.startsWith("io.vidocq.")) {
            return null;
        }
        String[] parts = moduleName.split("\\.");
        if (parts.length < 3 || parts[2].equals("runtime") || parts[2].equals("tools")) {
            return null;
        }
        return parts[2];
    }

    private static void addWithParents(ModuleLayer layer, Set<ModuleLayer> layers) {
        if (layer != null && layers.add(layer)) {
            layer.parents().forEach(parent -> addWithParents(parent, layers));
        }
    }

    private String java(boolean withVendor) {
        return "Java " + javaVersion + (withVendor && javaVendor != null ? " (" + javaVendor + ")" : "");
    }

    private String context(boolean withVendor, String name, boolean withReason, boolean withDebug) {
        List<String> parts = new ArrayList<>(4);
        parts.add(java(withVendor));
        String mode = launch == null ? null : withReason ? launch.text() : launch.shortText();
        if (mode != null) {
            parts.add(mode);
        }
        if (withDebug && debug != null) {
            parts.add(debug);
        }
        if (name != null) {
            parts.add(appVersion == null ? name : name + " " + appVersion);
        }
        return String.join(" | ", parts);
    }

    private static Map<String, List<String>> anchors() {
        Map<String, List<String>> anchors = new LinkedHashMap<>();
        anchors.put("cassini", List.of("io.vidocq.cassini.spi.bean.BeanProvider"));
        anchors.put("cervantes", List.of("io.vidocq.cervantes.api.JwtValidator"));
        anchors.put("champollion", List.of("io.vidocq.champollion.spi.RawJsonKeyWriter"));
        anchors.put("chappe", List.of("io.vidocq.chappe.api.StatusCode"));
        anchors.put("cyrano", List.of("io.vidocq.cyrano.spi.Cyrano"));
        anchors.put("dirac", List.of("io.vidocq.dirac.api.DiracException"));
        anchors.put("foy", List.of("io.vidocq.foy.spi.session.SessionStore"));
        anchors.put("grimm", List.of("io.vidocq.grimm.internal.config.ScanConfig"));
        anchors.put("heisenberg", List.of("io.vidocq.heisenberg.api.FaultToleranceException"));
        anchors.put("humboldt", List.of("io.vidocq.humboldt.Humboldt"));
        anchors.put("knock", List.of("io.vidocq.knock.spi.Knock"));
        anchors.put("mansart", List.of("io.vidocq.mansart.data.core.RepositoryRuntime",
                "io.vidocq.mansart.pool.PoolConfig", "io.vidocq.mansart.transactions.api.MansartTransactions"));
        anchors.put("ravel", List.of("io.vidocq.ravel.spi.Ravel"));
        anchors.put("vauban", List.of("io.vidocq.vauban.core.weaving.LoadTimeWeaving"));
        return Collections.unmodifiableMap(anchors);
    }
}

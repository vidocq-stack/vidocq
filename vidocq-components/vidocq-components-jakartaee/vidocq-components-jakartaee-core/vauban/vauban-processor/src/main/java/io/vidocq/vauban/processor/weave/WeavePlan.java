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
package io.vidocq.vauban.processor.weave;

import io.vidocq.vauban.weaver.ProxyLinkWeaver;
import io.vidocq.vauban.weaver.ProxyLinkWeaver.SuperChain;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The handshake between {@code VaubanProcessor} and {@link VaubanWeavingPlugin}
 * (Vidocq/vauban#24, javac tier of the weaving architecture).
 *
 * <p>Annotation processing runs <em>before</em> javac writes the class files, so the
 * processor cannot weave the {@code (ProxyLink)} entry constructor itself; and the javac
 * plugin — public API only — has no access to the {@code -d} directory. The processor,
 * which learns that directory from its {@link Filer}, therefore {@linkplain #publish
 * publishes} a small <em>weave plan</em> into the class output, and runs it at
 * {@code COMPILATION finished} of its own javac task, through a listener on that task.
 *
 * <p>Where the processing environment is not javac's own (a build tool that wraps it),
 * the processor cannot reach its task: it registers the directory in a JVM-global system
 * property instead, and at {@code COMPILATION finished} the plugin {@linkplain
 * #executeRegisteredPlans() executes} every registered plan. System properties are that
 * channel because javac loads processors and plugins in distinct class loaders — statics
 * do not cross. In a parallel build ({@code mvn -T}) that channel lets a task run the plan
 * of another task still writing its class files (BUG-20261121-05), so it is the fallback
 * only.
 *
 * <p>Plan line format: {@code bean <fqn> <NO_ARG|MARKER>} (weave the marker, chaining the
 * superclass accordingly) and {@code proxy <fqn>} (retarget the generated proxy's
 * {@code <init>} onto the marker). Execution is idempotent; a fully-executed plan is
 * deleted along with its property, so nothing leaks into the packaged artifact. A plan
 * whose class files are not all present yet (concurrent task in the same JVM) is kept for
 * the owning task's own event.
 */
public final class WeavePlan {

    /** Plan location under the class output — removed once fully executed. */
    public static final String PLAN_RESOURCE = "META-INF/vauban/weave-plan";

    private static final String PROP_PREFIX = "vauban.weave.plan.";

    /** The registry of the plans, and the lock of their execution: one object for every class loader of the JVM. */
    private static final Object REGISTRY = System.getProperties();
    private static final System.Logger LOG = System.getLogger(WeavePlan.class.getName());

    private WeavePlan() {}

    /** One plan instruction — see the class javadoc for the serialized form. */
    public record Line(Kind kind, String fqn, SuperChain chain) {
        public enum Kind { BEAN, PROXY }

        public static Line bean(String fqn, SuperChain chain) {
            return new Line(Kind.BEAN, fqn, chain);
        }

        public static Line proxy(String fqn) {
            return new Line(Kind.PROXY, fqn, null);
        }

        String serialize() {
            return kind == Kind.BEAN ? "bean " + fqn + " " + chain : "proxy " + fqn;
        }

        static Line parse(String raw) {
            var parts = raw.trim().split(" ", -1);
            return switch (parts[0]) {
                case "bean" -> bean(parts[1], SuperChain.valueOf(parts[2]));
                case "proxy" -> proxy(parts[1]);
                default -> throw new IllegalArgumentException("unknown weave-plan line: " + raw);
            };
        }
    }

    /**
     * Writes the plan into {@code CLASS_OUTPUT} and registers its directory for the
     * plugin. Call at most once per compilation (the {@link Filer} rejects a second
     * resource creation anyway).
     */
    public static void publish(ProcessingEnvironment processingEnv, List<Line> lines) throws IOException {
        if (lines.isEmpty()) return;
        var resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", PLAN_RESOURCE);
        try (var writer = resource.openWriter()) {
            for (var line : lines) {
                writer.write(line.serialize());
                writer.write('\n');
            }
        }
        var uri = resource.toUri();
        if ("file".equals(uri.getScheme())) {
            // …/META-INF/vauban/weave-plan → three levels up is the class output directory.
            Path outputDir = Path.of(uri).getParent().getParent().getParent();
            if (!runAtTheEndOfItsTask(processingEnv, outputDir)) {
                System.setProperty(PROP_PREFIX + outputDir, outputDir.toString());
            }
        } else {
            LOG.log(System.Logger.Level.WARNING,
                    "[Vauban] weave plan written to a non-file output ({0}) — "
                            + "the javac weaving plugin cannot execute it", uri);
        }
    }

    /**
     * Runs the plan of {@code outputDir} when the javac task of this processing environment ends; returns
     * {@code false} when the environment is not javac's own, and the task out of reach.
     */
    private static boolean runAtTheEndOfItsTask(ProcessingEnvironment processingEnv, Path outputDir) {
        JavacTask task;
        try {
            task = JavacTask.instance(processingEnv);
        } catch (IllegalArgumentException _) {
            return false;
        }
        task.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() == TaskEvent.Kind.COMPILATION) {
                    executeOwnPlan(outputDir);
                }
            }
        });
        return true;
    }

    /** Executes the plan of a task that has written all its class files: what is missing then is missing for good. */
    private static void executeOwnPlan(Path outputDir) {
        Path planFile = outputDir.resolve(PLAN_RESOURCE);
        try {
            if (Files.isRegularFile(planFile) && execute(outputDir, planFile)) {
                Files.deleteIfExists(planFile);
            } else if (Files.isRegularFile(planFile)) {
                LOG.log(System.Logger.Level.WARNING,
                        "[Vauban] weave plan {0} names a class this compilation did not write", planFile);
            }
        } catch (IOException | UncheckedIOException e) {
            LOG.log(System.Logger.Level.ERROR,
                    "[Vauban] failed to execute weave plan " + planFile, e);
        }
    }

    /**
     * Executes every registered plan whose class files are present, then unregisters it.
     * Invoked by {@link VaubanWeavingPlugin} at the end of each javac task; safe to call
     * from several tasks of the same JVM (idempotent weaving, incomplete plans are kept).
     *
     * <p>In a parallel build ({@code mvn -T}) a task also runs the plans of the tasks still
     * compiling (BUG-20261121-05): the plans run one at a time, under a lock shared by the
     * class loaders of every task (the system properties, the registry itself), and a class
     * file still being written counts as absent.
     */
    public static void executeRegisteredPlans() {
        synchronized (REGISTRY) {
            for (var property : System.getProperties().stringPropertyNames()) {
                if (property.startsWith(PROP_PREFIX)) {
                    executeRegisteredPlan(property);
                }
            }
        }
    }

    /** Executes the plan registered under {@code property}; unregisters it once done or gone. */
    private static void executeRegisteredPlan(String property) {
        Path outputDir = Path.of(System.getProperty(property));
        Path planFile = outputDir.resolve(PLAN_RESOURCE);
        try {
            if (!Files.isRegularFile(planFile)) {
                System.clearProperty(property);
            } else if (execute(outputDir, planFile)) {
                Files.deleteIfExists(planFile);
                System.clearProperty(property);
            }
        } catch (IOException | UncheckedIOException e) {
            // Loud, never silent: an unexecuted plan means the boot validator will
            // report the unwoven bean with a clear message anyway.
            LOG.log(System.Logger.Level.ERROR,
                    "[Vauban] failed to execute weave plan " + planFile, e);
        }
    }

    /** Runs the plan; returns {@code true} when every line found its class file. */
    private static boolean execute(Path outputDir, Path planFile) throws IOException {
        boolean complete = true;
        for (var raw : Files.readAllLines(planFile)) {
            if (!raw.isBlank() && !executeLine(outputDir, Line.parse(raw))) {
                complete = false;
            }
        }
        return complete;
    }

    /**
     * Weaves the class file of one line; returns {@code false} when that class file is absent, or not a whole class
     * file yet: another javac task of the JVM is still writing it.
     */
    private static boolean executeLine(Path outputDir, Line line) throws IOException {
        Path classFile = outputDir.resolve(line.fqn().replace('.', '/') + ".class");
        if (!Files.isRegularFile(classFile)) {
            return false;
        }
        byte[] bytes = Files.readAllBytes(classFile);
        byte[] result;
        try {
            result = switch (line.kind()) {
                case BEAN -> ProxyLinkWeaver.addMarkerConstructor(bytes, line.chain());
                case PROXY -> ProxyLinkWeaver.retargetProxyConstructor(bytes);
            };
        } catch (IllegalArgumentException _) {
            return false;
        }
        if (result != null) {
            Files.write(classFile, result);
        }
        return true;
    }
}

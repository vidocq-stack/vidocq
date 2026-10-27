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
package io.vidocq.runtime.arquillian;

import io.vidocq.ibarra.compiler.GenerationOptions;
import io.vidocq.ibarra.compiler.JspConfigurationParser;
import io.vidocq.ibarra.compiler.JspPropertyGroup;
import io.vidocq.ibarra.compiler.PagePrecompiler;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

/**
 * The JSP pages of a deployed war, compiled by Ibarra and served through Foy's JSP engine service, for the
 * Servlet TCK on the assembled runtime (Vidocq/vidocq#232 follow-up).
 *
 * <p>The embedded container runs on the class path, where the runtime gets no application layer: the pages are
 * compiled at deployment into a module, {@value #MODULE}, defined in a layer whose class loaders all end at the
 * deployment class loader (as {@code foy-tck} does, foy#52). The modules the pages require are resolved from their
 * real descriptors in a parent layer, defined to a loader that defines nothing. Foy looks the engine up with
 * {@code ServiceLoader} in that layer, and a provider must belong to a module of it, so the pages module also holds a
 * {@code JspEngine} that delegates to Ibarra's. {@link DeploymentApplicationLayer} hands the layer to the runtime.
 *
 * <p>Used only when Ibarra's compiler is on the class path (a runner that wants the JSP tests declares it).
 */
final class WarJspPages {

    static final String MODULE = "vidocq.tck.pages";
    private static final String ENGINE = MODULE + ".engine.Engine";

    private static final Set<String> COMPILE_MODULES = Set.of("io.vidocq.ibarra.api", "io.vidocq.ibarra.core",
            "io.vidocq.peano.api", "io.vidocq.peano.core", "io.vidocq.foy.api", "jakarta.servlet",
            "jakarta.servlet.jsp", "jakarta.el");

    private WarJspPages() {}

    /** Whether Ibarra's page compiler is on the class path. */
    static boolean available() {
        try {
            Class.forName("io.vidocq.ibarra.compiler.PagePrecompiler", false, WarJspPages.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException _) {
            return false;
        }
    }

    /**
     * The layer of the pages of the war materialized under {@code root}, compiled under {@code work}; empty when the
     * war has no page, tag file or tag library descriptor.
     */
    static Optional<ModuleLayer> compile(Path root, ClassLoader loader, Path work) throws IOException {
        Path web = root.resolve("META-INF/resources");
        List<Path> libraries = libraries(web);
        if (!hasPageSources(web, libraries)) return Optional.empty();
        Path output = Files.createDirectories(work.resolve("module"));
        Path warClasses = Files.createDirectories(work.resolve("war-classes"));
        copyClasses(root, warClasses);
        unpackLibraries(libraries, warClasses);

        List<Path> modulePath = compileModulePath();
        Set<String> warPackages = packagesOf(warClasses);
        copyTree(warClasses, output);
        Path sources = Files.createDirectories(work.resolve("src"));
        Path descriptor = sources.resolve("module-info.java");
        Files.writeString(descriptor, "module " + MODULE + " { requires java.desktop; requires java.naming; "
                + "requires jakarta.el; requires jakarta.servlet; requires jakarta.servlet.jsp; "
                + "requires io.vidocq.ibarra.api; requires io.vidocq.foy.api; "
                + "provides io.vidocq.foy.spi.jsp.JspEngine with " + ENGINE + "; }");
        Path engine = sources.resolve(ENGINE.replace('.', '/') + ".java");
        Files.createDirectories(engine.getParent());
        Files.writeString(engine, engineSource());
        javac("--module-path", join(modulePath), "--patch-module", MODULE + "=" + output,
                "-d", output.toString(), descriptor.toString(), engine.toString());

        Path webXml = web.resolve("WEB-INF/web.xml");
        List<JspPropertyGroup> groups = Files.exists(webXml)
                ? new JspConfigurationParser().parse("/WEB-INF/web.xml", Files.readAllBytes(webXml))
                : List.of();
        var result = new PagePrecompiler().precompileDeployment(web, output, modulePath, null,
                new GenerationOptions(groups, Map.of()));
        new TreeMap<>(result.failures()).forEach((page, failure) ->
                System.err.println("[VidocqTCK] JSP translation failed: " + page + ": " + failure));
        return Optional.of(define(output, warPackages, modulePath, loader));
    }

    /** A {@code JspEngine} in the pages module, so {@code ServiceLoader} finds it in the layer: it delegates to Ibarra's. */
    private static String engineSource() {
        return """
                package vidocq.tck.pages.engine;

                public final class Engine implements io.vidocq.foy.spi.jsp.JspEngine {
                    private final io.vidocq.foy.spi.jsp.JspEngine ibarra;

                    public Engine() throws ReflectiveOperationException {
                        ibarra = (io.vidocq.foy.spi.jsp.JspEngine) Class
                                .forName("io.vidocq.ibarra.foy.internal.IbarraJspEngine", true,
                                        Engine.class.getClassLoader())
                                .getConstructor().newInstance();
                    }

                    @Override
                    public Application deploy(jakarta.servlet.ServletContext context, ModuleLayer layer)
                            throws jakarta.servlet.ServletException {
                        return ibarra.deploy(context, layer);
                    }

                    @Override
                    public boolean hasPages(ModuleLayer layer) {
                        return ibarra.hasPages(layer);
                    }
                }
                """;
    }

    private static List<Path> libraries(Path web) throws IOException {
        Path lib = web.resolve("WEB-INF/lib");
        if (!Files.isDirectory(lib)) return List.of();
        try (Stream<Path> files = Files.list(lib)) {
            return files.filter(f -> f.toString().endsWith(".jar")).sorted().toList();
        }
    }

    private static boolean isPageSource(String path) {
        return path.endsWith(".jsp") || path.endsWith(".jspx") || path.endsWith(".jspf") || path.endsWith(".tag")
                || path.endsWith(".tagx") || path.endsWith(".tld");
    }

    /** Whether the war holds a page, a tag file or a tag library descriptor, its lib jars' included. */
    private static boolean hasPageSources(Path web, List<Path> libraries) throws IOException {
        if (Files.isDirectory(web)) {
            try (Stream<Path> files = Files.walk(web)) {
                if (files.anyMatch(f -> Files.isRegularFile(f) && isPageSource(f.toString())
                        && !f.toString().contains("/WEB-INF/classes/"))) return true;
            }
        }
        for (Path jar : libraries) {
            try (var file = new JarFile(jar.toFile())) {
                if (file.stream().anyMatch(e -> e.getName().startsWith("META-INF/") && e.getName().endsWith(".tld"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The war's class files, materialized at {@code root} next to {@code META-INF} and {@code WEB-INF}. */
    private static void copyClasses(Path root, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".class")).toList()) {
                String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (relative.startsWith("META-INF/") || relative.startsWith("WEB-INF/")) continue;
                Path out = target.resolve(relative);
                Files.createDirectories(out.getParent());
                Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** The classes of the lib jars, and their tag library descriptors, each jar's in a {@code META-INF} directory of its own. */
    private static void unpackLibraries(List<Path> libraries, Path target) throws IOException {
        int index = 0;
        for (Path jar : libraries) {
            String tlds = "META-INF/war-libraries/" + String.format(java.util.Locale.ROOT, "%04d", index++) + "/";
            try (var in = new JarInputStream(Files.newInputStream(jar))) {
                for (JarEntry entry; (entry = in.getNextJarEntry()) != null; ) {
                    String name = entry.getName();
                    if (entry.isDirectory() || name.equals("module-info.class") || name.startsWith("META-INF/versions/")) continue;
                    if (name.startsWith("META-INF/") && !name.endsWith(".tld")) continue;
                    Path out = target.resolve(name.startsWith("META-INF/") ? tlds + name : name).normalize();
                    if (!out.startsWith(target)) continue;
                    Files.createDirectories(out.getParent());
                    Files.write(out, in.readAllBytes());
                }
            }
        }
    }

    private static ModuleLayer define(Path output, Set<String> warPackages, List<Path> modulePath, ClassLoader loader)
            throws IOException {
        ModuleDescriptor compiled;
        try (var input = Files.newInputStream(output.resolve("module-info.class"))) {
            compiled = ModuleDescriptor.read(input);
        }
        ModuleLayer boot = ModuleLayer.boot();
        ModuleFinder apis = ModuleFinder.of(modulePath.toArray(Path[]::new));
        Set<String> required = new TreeSet<>();
        for (var read : compiled.requires()) if (apis.find(read.name()).isPresent()) required.add(read.name());
        var apiConfiguration = boot.configuration().resolve(apis, ModuleFinder.of(), required);
        ClassLoader relay = new ClassLoader("vidocq-tck-pages-apis", loader) {};
        ModuleLayer apiLayer = ModuleLayer.defineModules(apiConfiguration, List.of(boot), name -> relay).layer();

        Set<String> packages = new TreeSet<>(packagesOf(output));
        packages.removeAll(warPackages);
        var builder = ModuleDescriptor.newModule(MODULE).packages(packages);
        compiled.requires().stream()
                .filter(read -> !read.modifiers().contains(ModuleDescriptor.Requires.Modifier.MANDATED))
                .forEach(read -> builder.requires(read.name()));
        for (var provides : compiled.provides()) {
            List<String> generated = provides.providers().stream()
                    .filter(provider -> packages.contains(provider.substring(0, provider.lastIndexOf('.'))))
                    .toList();
            if (!generated.isEmpty()) builder.provides(provides.service(), generated);
        }
        ModuleReference reference = new DirectoryModule(builder.build(), output);
        ModuleFinder finder = new ModuleFinder() {
            @Override public Optional<ModuleReference> find(String name) {
                return name.equals(MODULE) ? Optional.of(reference) : Optional.empty();
            }
            @Override public Set<ModuleReference> findAll() { return Set.of(reference); }
        };
        var configuration = apiConfiguration.resolve(finder, ModuleFinder.of(), Set.of(MODULE));
        var controller = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(apiLayer), loader);
        Module pages = controller.layer().findModule(MODULE).orElseThrow();
        // The war's classes come from the deployment loader, the runtime's (PageIndex, JspEngine) from its parents:
        // the pages read the unnamed module of each loader of the chain.
        for (ClassLoader l = loader; l != null; l = l.getParent()) {
            controller.addReads(pages, l.getUnnamedModule());
            for (String pn : packages) controller.addExports(pages, pn, l.getUnnamedModule());
        }
        return controller.layer();
    }

    private static Set<String> packagesOf(Path root) throws IOException {
        var packages = new TreeSet<String>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(file -> file.toString().endsWith(".class") && !file.getFileName().toString().equals("module-info.class"))
                    .map(file -> root.relativize(file.getParent()))
                    .map(dir -> dir.toString().replace(File.separatorChar, '.'))
                    .filter(pn -> !pn.isEmpty())
                    .forEach(packages::add);
        }
        return packages;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static List<Path> compileModulePath() throws IOException {
        var candidates = new ArrayList<Path>();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String item : classPath.split(File.pathSeparator)) {
            if (!item.endsWith(".jar")) continue;
            Path jar = Path.of(item);
            if (!Files.isRegularFile(jar)) continue;
            try (var file = new JarFile(jar.toFile())) {
                if (file.getJarEntry("module-info.class") != null) candidates.add(jar);
            }
        }
        return ModuleFinder.of(candidates.toArray(Path[]::new)).findAll().stream()
                .filter(reference -> COMPILE_MODULES.contains(reference.descriptor().name()))
                .map(reference -> Path.of(reference.location().orElseThrow()))
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static String join(List<Path> paths) {
        return String.join(File.pathSeparator, paths.stream().map(Path::toString).toList());
    }

    private static void javac(String... arguments) {
        var errors = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors, arguments);
        if (status != 0) {
            throw new IllegalStateException("javac failed: " + errors.toString(StandardCharsets.UTF_8));
        }
    }

    private static final class DirectoryModule extends ModuleReference {
        private final Path root;

        DirectoryModule(ModuleDescriptor descriptor, Path root) {
            super(descriptor, root.toUri());
            this.root = root;
        }

        @Override
        public ModuleReader open() {
            return new ModuleReader() {
                @Override public Optional<URI> find(String name) {
                    Path file = root.resolve(name).normalize();
                    return file.startsWith(root) && Files.exists(file) ? Optional.of(file.toUri()) : Optional.empty();
                }
                @Override public Stream<String> list() {
                    try (Stream<Path> files = Files.walk(root)) {
                        return files.filter(Files::isRegularFile)
                                .map(file -> root.relativize(file).toString().replace(File.separatorChar, '/'))
                                .toList().stream();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                @Override public void close() {
                    // nothing to release: every read opens and closes its own file
                }
            };
        }
    }
}

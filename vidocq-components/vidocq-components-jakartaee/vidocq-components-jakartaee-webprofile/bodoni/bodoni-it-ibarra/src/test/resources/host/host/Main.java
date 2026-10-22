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
package host;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.DescriptorMerger;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.container.VidocqServletContext.ResourceProvider;
import io.vidocq.foy.internal.webxml.WebXmlParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleFinder;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Minimal Foy host, simplified from Ibarra's {@code ibarra-tck} host.
 *
 * <p>Arguments: the application module path (application classes, Bodoni and the JSTL API,
 * separated by the platform path separator), the application module name, the web root
 * and the context path. The application is defined in its own module layer, like a WAR's
 * {@code WEB-INF/lib}; the host prints {@code READY:<port>} and stops when its standard
 * input receives a byte or closes.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        for (String forbidden : List.of("java.compiler", "io.vidocq.ibarra.compiler", "io.vidocq.peano.compiler")) {
            if (ModuleLayer.boot().findModule(forbidden).isPresent()) {
                throw new IllegalStateException("Build tooling present in runtime: " + forbidden);
            }
        }
        Path[] applicationPath = Arrays.stream(args[0].split(File.pathSeparator)).map(Path::of).toArray(Path[]::new);
        String application = args[1];
        Path webRoot = Path.of(args[2]);
        String contextPath = args[3];

        var configuration = ModuleLayer.boot().configuration()
                .resolveAndBind(ModuleFinder.of(applicationPath), ModuleFinder.of(), Set.of(application));
        var layer = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(ModuleLayer.boot()),
                ClassLoader.getSystemClassLoader()).layer();
        var options = DeployOptions.defaults(layer.findLoader(application))
                .withResourceProvider(new Resources(webRoot));
        var model = WebAppModel.builder(contextPath);
        Path descriptor = webRoot.resolve("WEB-INF/web.xml");
        if (Files.exists(descriptor)) {
            try (var input = Files.newInputStream(descriptor)) {
                DescriptorMerger.merge(WebXmlParser.parse(input), DescriptorMerger.AnnotatedComponents.none(),
                        options.componentFactory(), model);
            }
        }
        try (var deployment = WebAppDeployer.deploy(model.build(), options, layer);
             var server = Server.builder().host("127.0.0.1").port(0).handler(deployment.handler()).build()) {
            server.start();
            System.out.println("READY:" + server.port());
            System.out.flush();
            System.in.read();
            server.stop();
        }
    }

    /** Serves the web root's static resources (web.xml, page sources for diagnostics). */
    private record Resources(Path root) implements ResourceProvider {
        Resources {
            root = root.toAbsolutePath().normalize();
        }

        private Path resolve(String path) {
            if (path == null || !path.startsWith("/") || path.indexOf('\\') >= 0) {
                return null;
            }
            Path file = root.resolve(path.substring(1)).normalize();
            return file.startsWith(root) ? file : null;
        }

        @Override
        public InputStream openStream(String path) {
            Path file = resolve(path);
            if (file == null || !Files.isRegularFile(file)) {
                return null;
            }
            try {
                return Files.newInputStream(file);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public URL toUrl(String path) {
            Path file = resolve(path);
            if (file == null || !Files.exists(file)) {
                return null;
            }
            try {
                return file.toUri().toURL();
            } catch (MalformedURLException failure) {
                throw new IllegalArgumentException(failure);
            }
        }

        @Override
        public Set<String> listPaths(String path) {
            Path directory = resolve(path);
            if (directory == null || !Files.isDirectory(directory)) {
                return null;
            }
            try (var files = Files.list(directory)) {
                var result = new TreeSet<String>();
                files.forEach(file -> result.add("/" + root.relativize(file).toString().replace(File.separatorChar, '/')
                        + (Files.isDirectory(file) ? "/" : "")));
                return result.isEmpty() ? null : result;
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public Metadata metadata(String path) {
            Path file = resolve(path);
            if (file == null || !Files.isRegularFile(file)) {
                return null;
            }
            try {
                return new Metadata(Files.size(file), Files.getLastModifiedTime(file).toMillis());
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }
}

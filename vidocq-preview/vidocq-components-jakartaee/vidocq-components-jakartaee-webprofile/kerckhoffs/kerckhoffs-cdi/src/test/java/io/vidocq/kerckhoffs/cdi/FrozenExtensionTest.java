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
package io.vidocq.kerckhoffs.cdi;

import io.vidocq.kerckhoffs.core.internal.mechanism.BasicAuthenticationMechanism;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult.Status;
import jakarta.security.enterprise.identitystore.IdentityStoreHandler;
import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extension as the build freezes it: the start loads what it did instead of running it, and the
 * application behaves the same (Vidocq/vauban#288).
 */
class FrozenExtensionTest {

    private static final String SERVICE =
            "META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension";

    @TempDir
    Path tmp;

    private Path classes;

    /** A start's view of the application: its resources from its own path only, as a packaged application has. */
    private static final class ApplicationLoader extends URLClassLoader {
        ApplicationLoader(Path classes) throws IOException {
            super(new URL[] {classes.toUri().toURL()}, FrozenExtensionTest.class.getClassLoader());
        }

        @Override
        public URL getResource(String name) {
            return findResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return findResources(name);
        }
    }

    private static Path root(Class<?> type) throws Exception {
        return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /** Copies the class files of {@code types} into the application, each under its own package. */
    private void copy(Class<?>... types) throws Exception {
        for (Class<?> type : types) {
            String file = type.getName().replace('.', '/') + ".class";
            Path target = classes.resolve(file);
            Files.createDirectories(target.getParent());
            Files.copy(root(type).resolve(file), target);
        }
    }

    private void application(Class<?>... definitions) throws Exception {
        copy(KerckhoffsExtension.class, KerckhoffsExtension.Declared.class, InMemoryIdentityStoreCreator.class,
                IdentityStoreHandlerCreator.class, BasicMechanismCreator.class, Pbkdf2PasswordHashCreator.class,
                IdentityStoreStartCheck.class);
        copy(definitions);
        Files.createDirectories(classes.resolve(SERVICE).getParent());
        Files.writeString(classes.resolve(SERVICE), KerckhoffsExtension.class.getName() + "\n");
        // What the annotation processor writes: the beans of the archive, read by scanClasspath().
        var beans = new StringBuilder(IdentityStoreStartCheck.class.getName()).append('\n');
        for (Class<?> definition : definitions) beans.append(definition.getName()).append('\n');
        Files.writeString(classes.resolve("META-INF/vauban-beans.list"), beans.toString());
    }

    @BeforeEach
    void output() throws IOException {
        classes = Files.createDirectories(tmp.resolve("classes"));
    }

    private void freeze() throws Exception {
        try (var loader = new ApplicationLoader(classes)) {
            var outcome = VaubanContainer.builder().classLoader(loader).scanClasspath()
                    .freezeExtensions(classes, List.of());
            assertTrue(outcome.frozen(), outcome.reason());
        }
    }

    @Test
    void the_scanned_application_without_a_record_runs_the_extension_at_start() throws Exception {
        application(MemoryApplication.class);

        try (var loader = new ApplicationLoader(classes);
             var container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            assertEquals(Status.VALID, container.select(IdentityStoreHandler.class)
                    .validate(new UsernamePasswordCredential("reza", "secret1")).getStatus());
        }
    }

    @Test
    void the_start_loads_the_frozen_extension_and_the_application_behaves_the_same() throws Exception {
        application(MemoryApplication.class);
        freeze();

        try (var loader = new ApplicationLoader(classes);
             var container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            assertTrue(container.extensionsAtStart().frozen(), String.valueOf(container.extensionsAtStart().note()));
            assertEquals(List.of(), container.extensionsAtStart().ranAtStart());
            var handler = container.select(IdentityStoreHandler.class);

            assertEquals(Status.VALID, handler.validate(new UsernamePasswordCredential("reza", "secret1")).getStatus());
            assertEquals(Status.INVALID, handler.validate(new UsernamePasswordCredential("reza", "wrong")).getStatus());
            var hash = container.select(Pbkdf2PasswordHash.class);
            assertTrue(hash.verify("secret1".toCharArray(), hash.generate("secret1".toCharArray())),
                    "the built-in password hash is frozen with the rest");
        }
    }

    @Test
    void a_frozen_application_with_an_invalid_definition_still_stops_the_start() throws Exception {
        application(BrokenApplication.class);
        freeze();

        assertThrows(DeploymentException.class, this::startAndClose);
    }

    @Test
    void a_frozen_basic_definition_still_puts_the_mechanism_in_service() throws Exception {
        application(MemoryApplication.class, BasicApplication.class);
        freeze();

        try (var loader = new ApplicationLoader(classes);
             var container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            assertEquals(List.of(), container.extensionsAtStart().ranAtStart());
            assertInstanceOf(BasicAuthenticationMechanism.class, container.select(HttpAuthenticationMechanism.class));
        }
    }

    /** Starts the application from its output; the start itself is what a test asks to fail. */
    private void startAndClose() throws IOException {
        try (var loader = new ApplicationLoader(classes)) {
            VaubanContainer.builder().classLoader(loader).scanClasspath().build().close();
        }
    }
}

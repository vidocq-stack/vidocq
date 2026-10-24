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
package io.vidocq.runtime.junit;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Starts the application of a {@link VidocqTest} class once, keeps it in the class's store, which closes it after
 * the last test, and resolves {@link VidocqApplication} and {@link VidocqUrl} parameters. A {@code @Nested} class
 * shares the application of the class that carries the annotation: a store looks up its parents first.
 */
public final class VidocqTestExtension implements BeforeAllCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(VidocqTestExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        application(context);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == VidocqApplication.class
                || parameter.isAnnotated(VidocqUrl.class) && (type == URI.class || type == String.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        VidocqApplication application = application(context);
        Class<?> type = parameter.getParameter().getType();
        if (type == VidocqApplication.class) {
            return application;
        }
        if (type == URI.class) {
            return application.url();
        }
        if (type == String.class) {
            return application.url().toString();
        }
        throw new ParameterResolutionException("@VidocqUrl goes on a URI or a String parameter, not " + type);
    }

    /** The application of the annotated class, started on the first call, in the store of that class. */
    private static VidocqApplication application(ExtensionContext context) {
        Class<?> annotated = annotated(context.getRequiredTestClass());
        return context.getStore(NAMESPACE).computeIfAbsent(annotated, VidocqTestExtension::start,
                VidocqApplication.class);
    }

    /** The test class, or the class enclosing a {@code @Nested} one, that carries {@link VidocqTest}. */
    private static Class<?> annotated(Class<?> testClass) {
        for (Class<?> c = testClass; c != null; c = c.getEnclosingClass()) {
            if (c.isAnnotationPresent(VidocqTest.class)) {
                return c;
            }
        }
        throw new IllegalStateException(testClass.getName() + " is not annotated @VidocqTest");
    }

    private static VidocqApplication start(Class<?> testClass) {
        VidocqTest settings = testClass.getAnnotation(VidocqTest.class);
        String module = ApplicationLaunch.module(settings.module(), testClass.getModule().getName(),
                System.getProperty("vidocq.mainModule"));
        String modulePath = ApplicationLaunch.modulePath(System.getProperty("jdk.module.path"));
        String java = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        try {
            int port = VidocqApplication.freePort();
            return VidocqApplication.start(
                    ApplicationLaunch.command(java, modulePath, module, port, List.of(settings.properties())),
                    port, Duration.ofSeconds(settings.startupTimeoutSeconds()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the application module " + module, e);
        }
    }
}

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

import io.vidocq.runtime.spi.VidocqApp;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Vidocq.run(Class)} runs the application class as the CDI bean it is. A class with a bounded type variable
 * is a bean too, although its raw class does not look it up (CDI 4.1 section 5.2.4): it is not to be created by its
 * constructor, without its injections (BUG-20261117-11). The lookup asks for {@code @Default} first, then for the
 * bean of the class whatever its qualifiers (BUG-20261117-13), and the runtime destroys a {@code @Dependent} instance
 * when it stops (BUG-20261117-12).
 */
class ApplicationClassBeanTest {

    private VidocqBootstrap bootstrap;

    @BeforeEach
    void clean() {
        Vidocq.unregister();
        DependentApplication.DESTROYED.set(0);
        BoundedApplication.DESTROYED.set(0);
    }

    @AfterEach
    void stop() {
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
        Vidocq.unregister();
    }

    @Test
    void anApplicationClassThatIsABeanIsRunWithItsInjections() throws Exception {
        start(DependentApplication.class);

        VidocqApp application = Vidocq.instantiateInLayer(DependentApplication.class);

        assertEquals(1, application.run());
    }

    /** The class declares a producer, a second bean of that class: it is not the application. */
    @Test
    void anApplicationClassWithABoundedTypeVariableIsRunWithItsInjections() throws Exception {
        start(BoundedApplication.class);

        VidocqApp application = Vidocq.instantiateInLayer(BoundedApplication.class);

        assertEquals(1, application.run(), "1 when the container made the instance, 0 when its constructor did");
    }

    @Test
    void anApplicationClassThatIsNotABeanIsCreatedByItsConstructor() throws Exception {
        start();

        VidocqApp application = Vidocq.instantiateInLayer(PlainApplication.class);

        assertEquals(2, application.run());
    }

    @Test
    void anApplicationClassWithABoundedTypeVariableThatIsNotABeanIsCreatedByItsConstructor() throws Exception {
        start();

        VidocqApp application = Vidocq.instantiateInLayer(PlainBoundedApplication.class);

        assertEquals(3, application.run());
    }

    /**
     * When the container knows the class but cannot give its bean, the runtime still creates it by its constructor,
     * and says so: without the warning, an application without its injections is the only sign.
     */
    @Test
    void anApplicationClassTheContainerCannotCreateIsReportedBeforeItsConstructorRuns() throws Exception {
        start(FailingApplication.class);

        VidocqApp application;
        List<String> warnings;
        try (LogRecords logs = new LogRecords(Vidocq.class.getName())) {
            application = Vidocq.instantiateInLayer(FailingApplication.class);
            warnings = logs.messages(Vidocq.class.getName(), Level.WARNING);
        }

        assertEquals(4, application.run());
        assertEquals(1, warnings.size(), "one warning: " + warnings);
        assertTrue(warnings.getFirst().contains(FailingApplication.class.getName()), warnings.getFirst());
        assertTrue(warnings.getFirst().contains(FailingApplication.FAILURE), warnings.getFirst());
    }

    /**
     * The instance of a {@code @Dependent} application class with a bounded type variable is destroyed when the
     * runtime stops, as the one of any {@code @Dependent} application class (BUG-20261117-12).
     */
    @Test
    void theInstanceOfABoundedApplicationClassLivesAsLongAsTheOneOfAnyDependentApplicationClass() throws Exception {
        start(DependentApplication.class, BoundedApplication.class);
        Vidocq.instantiateInLayer(DependentApplication.class);
        Vidocq.instantiateInLayer(BoundedApplication.class);

        Vidocq.shutdownAndWait();

        assertEquals(1, DependentApplication.DESTROYED.get(), "instances destroyed once the runtime stopped");
        assertEquals(1, BoundedApplication.DESTROYED.get(), "instances of the bounded class destroyed as well");
    }

    /** The runtime destroys the instance it asked the container for, before the extensions stop (BUG-20261117-12). */
    @Test
    void theInstanceOfADependentApplicationClassIsDestroyedWhenTheRuntimeStops() throws Exception {
        start(DependentApplication.class);
        Vidocq.instantiateInLayer(DependentApplication.class);

        Vidocq.shutdownAndWait();

        assertEquals(1, DependentApplication.DESTROYED.get(), "instances destroyed once the runtime stopped");
    }

    /** The bean of the class itself, whatever its qualifiers, when none has {@code @Default} (BUG-20261117-13). */
    @Test
    void anApplicationClassWithAnotherQualifierIsRunWithItsInjections() throws Exception {
        start(TaggedApplication.class);

        VidocqApp application = Vidocq.instantiateInLayer(TaggedApplication.class);

        assertEquals(1, application.run(), "1 when the container made the instance, 0 when its constructor did");
    }

    private void start(Class<?>... applicationClasses) {
        bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF)
                .extensions(List.of(new VidocqExtension() {
                    @Override
                    public String name() {
                        return "application-class";
                    }

                    @Override
                    public void beforeStart(VaubanContainerBuilder builder) {
                        builder.addComponentProvider(new _VaubanComponents())
                                .addBeanClass(ApplicationCollaborator.class);
                        for (Class<?> applicationClass : applicationClasses) {
                            builder.addBeanClass(applicationClass);
                        }
                    }
                })).configure();
        Vidocq.register(bootstrap);
        bootstrap.start();
    }

    /** An application class that is not a bean. */
    public static class PlainApplication implements VidocqApp {

        @Override
        public int run(String... args) {
            return 2;
        }
    }

    /**
     * An application class with a bounded type variable that is not a bean.
     *
     * @param <T> unused by the application
     */
    public static class PlainBoundedApplication<T extends Number> implements VidocqApp {

        @Override
        public int run(String... args) {
            return 3;
        }
    }
}

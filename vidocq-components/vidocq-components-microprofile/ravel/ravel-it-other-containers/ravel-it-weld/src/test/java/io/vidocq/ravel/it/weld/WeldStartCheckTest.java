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
package io.vidocq.ravel.it.weld;

import io.vidocq.ravel.cdi.ConfigPropertiesExclusionExtension;
import jakarta.enterprise.event.ObserverException;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.weld.bootstrap.spi.BeanDiscoveryMode;
import org.jboss.weld.environment.se.Weld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MicroProfile Config 3.1 §6.1 and §6.4 under Weld SE (ravel#50): a configuration that does not
 * satisfy the injection points keeps the container from starting, with a
 * {@code DeploymentException}. Ravel's build compatible extension reads no configuration: it
 * registers a check for the start of the application, which the portable extension runs at
 * deployment validation on a full CDI container such as Weld.
 */
class WeldStartCheckTest {

    @Test
    void missingRequiredPropertyFailsTheStart() {
        DeploymentException failure = startFailure(containerOf(NeedsAMissingValue.class));

        assertTrue(messagesOf(failure).contains("Missing required config property 'it.weld.absent' for type"
                + " java.lang.String [at " + NeedsAMissingValue.class.getName() + "#absent]"), messagesOf(failure));
    }

    @Test
    void unconvertibleRequiredPropertyFailsTheStart() {
        // greeting=hello from ravel: not a number.
        DeploymentException failure = startFailure(containerOf(NeedsANumber.class));

        assertTrue(messagesOf(failure).contains("Invalid @ConfigProperty for key 'greeting'"), messagesOf(failure));
    }

    @Test
    void missingMemberOfAnInjectedConfigPropertiesClassFailsTheStart() {
        DeploymentException failure = startFailure(containerOf(NeedsIncompleteSettings.class));

        assertTrue(messagesOf(failure).contains("@ConfigProperties: missing required property"
                + " 'it.weld.incomplete.zone'"), messagesOf(failure));
    }

    @Test
    void missingMemberOfADiscoveredConfigPropertiesClassFailsTheStart() {
        // §6.4: checked even when nothing injects the class.
        DeploymentException failure = startFailure(containerOf(IncompleteSettings.class));

        assertTrue(messagesOf(failure).contains("@ConfigProperties: missing required property"
                + " 'it.weld.incomplete.zone'"), messagesOf(failure));
    }

    @Test
    void presentAndDefaultedPropertiesLetTheContainerStart() {
        Weld weld = containerOf(Satisfied.class);

        assertDoesNotThrow(() -> startAndStop(weld));
    }

    @Test
    void withoutThePortableExtensionTheStartupObserverFailsTheStart() {
        // Weld SE with discovery disabled loads no portable extension from META-INF/services, only the
        // build compatible ones: the check stays the Startup observer the extension registered, and
        // Weld reports what it throws as the cause of an ObserverException.
        Weld weld = explicitBeans(NeedsAMissingValue.class);

        ObserverException failure = assertThrows(ObserverException.class, () -> startAndStop(weld));

        DeploymentException cause = assertInstanceOf(DeploymentException.class, failure.getCause());
        assertTrue(cause.getMessage().contains("Missing required config property 'it.weld.absent' for type"
                + " java.lang.String [at " + NeedsAMissingValue.class.getName() + "#absent]"), cause.getMessage());
    }

    @Test
    void withoutThePortableExtensionASatisfiedConfigurationLetsTheContainerStart() {
        Weld weld = explicitBeans(Satisfied.class);

        assertDoesNotThrow(() -> startAndStop(weld));
    }

    /**
     * A container of these classes only, with Ravel's two extensions, as a discovering container has
     * them. Discovery is off so that the classes need no bean defining annotation, and the container
     * of {@link WeldPortabilityTest}, which discovers this archive, does not see them; Weld SE then
     * loads no portable extension by itself.
     */
    private static Weld containerOf(Class<?>... beanClasses) {
        return explicitBeans(beanClasses).addExtension(new ConfigPropertiesExclusionExtension());
    }

    private static Weld explicitBeans(Class<?>... beanClasses) {
        return new Weld().disableDiscovery().setBeanDiscoveryMode(BeanDiscoveryMode.ALL).addBeanClasses(beanClasses);
    }

    /**
     * The {@code DeploymentException} Weld itself throws: Ravel's portable extension runs the check
     * at deployment validation, so the problem is a deployment problem — not the exception of a
     * {@code Startup} observer, which Weld would throw as an {@code ObserverException}.
     */
    private static DeploymentException startFailure(Weld weld) {
        return assertThrows(DeploymentException.class, () -> startAndStop(weld));
    }

    private static void startAndStop(Weld weld) {
        weld.initialize().close();
    }

    private static String messagesOf(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }

    public static class NeedsAMissingValue {
        @Inject
        @ConfigProperty(name = "it.weld.absent")
        String absent;
    }

    public static class NeedsANumber {
        @Inject
        @ConfigProperty(name = "greeting")
        Integer number;
    }

    public static class NeedsIncompleteSettings {
        @Inject
        @ConfigProperties
        IncompleteSettings settings;
    }

    @ConfigProperties(prefix = "it.weld.incomplete")
    public static class IncompleteSettings {
        public String zone;
    }

    public static class Satisfied {
        @Inject
        @ConfigProperty(name = "greeting")
        String greeting;

        @Inject
        @ConfigProperty(name = "it.weld.absent", defaultValue = "7")
        int defaulted;
    }
}

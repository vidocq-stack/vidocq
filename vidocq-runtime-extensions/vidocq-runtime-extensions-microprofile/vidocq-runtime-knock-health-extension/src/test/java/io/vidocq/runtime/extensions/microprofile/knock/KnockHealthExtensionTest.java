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
package io.vidocq.runtime.extensions.microprofile.knock;

import io.vidocq.knock.runtime.HealthCheckRegistries;
import io.vidocq.knock.spi.CheckResult;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.Verbosity;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.eclipse.microprofile.health.HealthCheck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code health} section of the startup report: it reads the last results of the existing
 * {@link HealthCheckRegistry} bean, never creates it and never calls a check. The live values of the dev console
 * panel are covered by {@code HealthLivePanelTest} in {@code vidocq-runtime-knock-health-extension-dev}.
 */
class KnockHealthExtensionTest {

    /** A registry bean, as {@code KnockCdiHealthCheckRegistry} is: application scoped, delegating to Knock's core. */
    @ApplicationScoped
    static class TestRegistry implements HealthCheckRegistry {

        private final HealthCheckRegistry delegate = HealthCheckRegistries.newRegistry();

        @Override
        public void register(ProbeType type, String name, HealthCheck check) {
            delegate.register(type, name, check);
        }

        @Override
        public void unregister(String name) {
            delegate.unregister(name);
        }

        @Override
        public List<HealthCheck> getChecks(ProbeType type) {
            return delegate.getChecks(type);
        }

        @Override
        public Map<String, HealthCheck> getNamedChecks(ProbeType type) {
            return delegate.getNamedChecks(type);
        }

        @Override
        public Set<String> getCheckNames(ProbeType type) {
            return delegate.getCheckNames(type);
        }

        @Override
        public void recordResult(CheckResult result) {
            delegate.recordResult(result);
        }

        @Override
        public List<CheckResult> getLastResults() {
            return delegate.getLastResults();
        }
    }

    private final KnockHealthExtension extension = new KnockHealthExtension();

    @AfterEach
    void stop() {
        extension.onStop();
    }

    @Test
    void itIsAVidocqExtensionFoundByTheServiceLoaderAndContributesItsSection() {
        assertTrue(providedAsVidocqExtension(KnockHealthExtension.class));
        assertEquals("health", extension.id());
        assertEquals("Health (Knock)", extension.title());
        assertEquals("knock-health", extension.name());
    }

    @Test
    void aRegistryNotCreatedYetContributesNoCheckCreated() {
        try (VaubanContainer container = started(TestRegistry.class)) {
            RecordingSection section = new RecordingSection();
            extension.contribute(new FakeReportContext(Verbosity.DETAILED), section);

            assertEquals("no health check (registry not created yet)", section.summary);
            assertNull(existing(container.getBeanManager()), "the report created the registry bean");
        }
    }

    /** A container holding {@code beans}, with the extension started on it. */
    private VaubanContainer started(Class<?>... beans) {
        var builder = VaubanContainer.builder();
        for (Class<?> bean : beans) {
            builder.addBeanClass(bean);
        }
        VaubanContainer container = builder.build();
        extension.onStart(new FakeExtensionContext(container));
        return container;
    }

    private static Bean<?> registryBean(BeanManager beans) {
        Bean<?> bean = beans.resolve(beans.getBeans(HealthCheckRegistry.class));
        assertNotNull(bean, "the container holds no HealthCheckRegistry: the test proves nothing");
        return bean;
    }

    private static Object existing(BeanManager beans) {
        Bean<?> bean = registryBean(beans);
        return beans.getContext(bean.getScope()).get(bean);
    }

    /**
     * On the module path the test runs inside the extension's module, which provides {@link VidocqExtension}
     * without using it, so {@link ServiceLoader} refuses the lookup: read the {@code provides} clause there,
     * and ask {@link ServiceLoader} on the class path.
     */
    private static boolean providedAsVidocqExtension(Class<?> type) {
        Module module = type.getModule();
        if (module.isNamed()) {
            return module.getDescriptor().provides().stream()
                    .anyMatch(provides -> provides.service().equals(VidocqExtension.class.getName())
                            && provides.providers().contains(type.getName()));
        }
        return ServiceLoader.load(VidocqExtension.class).stream().anyMatch(provider -> provider.type() == type);
    }
}

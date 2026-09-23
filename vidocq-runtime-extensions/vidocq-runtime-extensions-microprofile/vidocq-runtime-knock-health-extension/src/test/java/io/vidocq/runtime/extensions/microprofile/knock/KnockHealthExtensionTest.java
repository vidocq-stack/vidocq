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
import io.vidocq.knock.runtime.KnockHealthService;
import io.vidocq.knock.spi.CheckResult;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.report.Verbosity;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code health} panel on a real Vauban container: it reads the last results of the existing
 * {@link HealthCheckRegistry} bean, never creates it and never calls a check.
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
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void stop() {
        extension.onStop();
    }

    @Test
    void itIsAVidocqExtensionFoundByTheServiceLoaderAndADevConsolePanel() {
        assertTrue(ServiceLoader.load(VidocqExtension.class).stream()
                .anyMatch(provider -> provider.type() == KnockHealthExtension.class));
        DevConsolePanel panel = assertInstanceOf(DevConsolePanel.class, extension);
        assertEquals("health", panel.id());
        assertEquals("Health (Knock)", panel.title());
        assertEquals("knock-health", extension.name());
        assertEquals(HealthPanel.CHARTS, panel.charts());
    }

    @Test
    void beforeOnStartTheSampleIsEmpty() {
        RecordingSample out = new RecordingSample();

        extension.sample(out);

        assertTrue(out.isEmpty());
    }

    @Test
    void aRegistryNotCreatedYetIsShownAbsentAndSamplingDoesNotCreateIt() {
        try (VaubanContainer container = started(TestRegistry.class)) {
            RecordingSample out = new RecordingSample();
            extension.sample(out);

            assertEquals("absent", out.kind("checks"));
            assertEquals(KnockLiveBean.NOT_CREATED_YET, out.text("checks"));
            assertNull(existing(container.getBeanManager()), "sampling created the registry bean");

            RecordingSection section = new RecordingSection();
            extension.contribute(new FakeReportContext(Verbosity.DETAILED), section);
            assertNull(existing(container.getBeanManager()), "the report created the registry bean");
        }
    }

    @Test
    void theLastResultsOfTheExistingRegistryAreShownWithoutCallingACheck() {
        try (VaubanContainer container = started(TestRegistry.class)) {
            HealthCheckRegistry registry = create(container.getBeanManager());
            registry.register(ProbeType.LIVENESS, "com.acme.AppLivenessCheck_ClientProxy", () -> {
                calls.incrementAndGet();
                return HealthCheckResponse.up("app");
            });
            RecordingSample before = new RecordingSample();
            extension.sample(before);

            new KnockHealthService(registry).report(ProbeType.ALL);
            RecordingSample after = new RecordingSample();
            extension.sample(after);

            assertEquals(HealthPanel.NEVER_CALLED, before.groups().get("liveness").text("app-liveness-check"));
            assertEquals(1, after.groups().get("liveness").number("app-liveness-check"));
            assertEquals(1, calls.get(), "only the probe request called the check");
        }
    }

    @Test
    void aContainerWithoutKnockSaysSo() {
        try (VaubanContainer container = started()) {
            RecordingSample out = new RecordingSample();
            extension.sample(out);

            assertEquals(KnockLiveBean.NOT_DEPLOYED, out.text("checks"));
        }
    }

    @Test
    void afterOnStopTheSampleIsEmpty() {
        try (VaubanContainer container = started(TestRegistry.class)) {
            create(container.getBeanManager());
            extension.onStop();
            RecordingSample out = new RecordingSample();

            extension.sample(out);

            assertTrue(out.isEmpty());
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

    /** Creates the registry bean as the application would, by asking its context for it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static HealthCheckRegistry create(BeanManager beans) {
        Bean bean = registryBean(beans);
        Context context = beans.getContext(bean.getScope());
        return (HealthCheckRegistry) context.get(bean, beans.createCreationalContext(bean));
    }
}

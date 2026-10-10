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
package io.vidocq.humboldt.tck.arquillian;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.vidocq.humboldt.otel.interop.OtelSpiAutoConfiguration;
import io.vidocq.humboldt.runtime.AutoConfiguredHumboldt;
import io.vidocq.humboldt.runtime.EnvConfig;
import io.vidocq.humboldt.runtime.HumboldtAutoConfigure;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.humboldt.rest.HumboldtServerRequestFilter;
import io.vidocq.humboldt.rest.HumboldtServerResponseFilter;
import io.vidocq.humboldt.rest.HumboldtSpanFinalizer;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Node;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * "Embedded" Humboldt Arquillian container — assembles Vauban CDI Lite +
 * (future Cassini JAX-RS / Chappe HTTP) + in-process humboldt-runtime to
 * execute the MicroProfile Telemetry 2.2 TCK.
 *
 * <p>From-scratch approach (see {@code tasks/m7b-architecture-analysis.md}
 * Option C) — no reuse of vidocq to avoid the
 * humboldt-tck → vidocq → humboldt dependency cycle.</p>
 *
 * <p>Incremental progression:</p>
 * <ul>
 *   <li><strong>M7b.4b.1</strong> ✅ Skeleton: lifecycle start/stop, NoOp deploy</li>
 *   <li><strong>M7b.4b.2</strong> ✅ Deploy: boot Vauban CDI on WAR classes
 *       + AutoConfiguredHumboldt with hardcoded env config</li>
 *   <li><strong>M7b.4b.3</strong> OTel SDK autoconfigure bridge (parse
 *       microprofile-config.properties; the providers the archive declares are
 *       discovered by humboldt-otel-interop's {@link OtelSpiAutoConfiguration}
 *       through a {@link DeploymentServicesClassLoader})</li>
 *   <li><strong>M7b.4b.4</strong> CDI TestEnricher for
 *       {@code @Inject} injection into the test class</li>
 * </ul>
 *
 * <p>Arquillian protocol used: <em>Local</em> — tests run in the
 * same JVM as the container. The test can therefore access CDI through
 * {@link VaubanContainer#current()} while waiting for enricher M7b.4b.4.</p>
 */
public class HumboldtDeployableContainer implements DeployableContainer<HumboldtContainerConfig> {

    private static final Logger LOG = System.getLogger(HumboldtDeployableContainer.class.getName());

    /** Internal ShrinkWrap prefix for classes in a {@code WebArchive}. */
    private static final String WEB_INF_CLASSES_PREFIX = "WEB-INF/classes/";

    private VaubanContainer container;
    private AutoConfiguredHumboldt humboldt;
    private CassiniHarness cassini;

    @Override
    public Class<HumboldtContainerConfig> getConfigurationClass() {
        return HumboldtContainerConfig.class;
    }

    @Override
    public void setup(HumboldtContainerConfig configuration) {
        LOG.log(Level.INFO, "Humboldt Arquillian container — setup");
    }

    @Override
    public void start() throws LifecycleException {
        LOG.log(Level.INFO, "Humboldt Arquillian container — start");
    }

    @Override
    public void stop() throws LifecycleException {
        LOG.log(Level.INFO, "Humboldt Arquillian container — stop");
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Local");
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        LOG.log(Level.INFO, "Humboldt Arquillian container — deploy {0}", archive.getName());
        try {
            List<Class<?>> beanClasses = extractBeanClasses(archive);
            LOG.log(Level.INFO, "  → {0} bean class(es) extracted from archive", beanClasses.size());

            VaubanContainerBuilder builder = VaubanContainer.builder();
            // Standard CDI producers from MP Telemetry §"Required CDI beans":
            // @Inject Tracer / Span / Baggage / OpenTelemetry — provided by
            // humboldt-cdi, added systematically to each deploy.
            builder.addBeanClass(io.vidocq.humboldt.cdi.HumboldtTelemetryProducers.class);
            // HBT-1 — Cassini @Path → @RequestScoped BCE: Vauban applies @Enhancement
            // BCEs to "unprocessed" classes through BceProcessor.processEnhancementOnly()
            // (see VaubanContainerBuilder.java:742), but ONLY if the BCE is in the
            // bean classes set. addBeanClass() does NOT scan the ServiceLoader. So add the
            // Cassini BCE manually here so that the WAR's @Path resources (for example
            // BaggageResource, RestSpanTest$SpanResource) receive a synthetic
            // @RequestScoped and are discovered as Vauban beans → @Inject Baggage/Tracer
            // on resource instances remains non-null.
            builder.addBeanClass(io.vidocq.cassini.cdi.CassiniScopeExtension.class);
            // Same issue for the Humboldt BCE: HumboldtBuildCompatibleExtension scans
            // classes annotated with @WithSpan (OTel) and adds @SpanBinding to activate
            // WithSpanInterceptor. Without this BCE, TCK inner classes such as
            // RestClientSpanTest$SpanBean that carry @WithSpan never generate the expected
            // INTERNAL span (SERVER → CLIENT → INTERNAL chain incomplete).
            builder.addBeanClass(io.vidocq.humboldt.cdi.HumboldtBuildCompatibleExtension.class);
            // And the interceptor itself, otherwise @SpanBinding has no runtime effect.
            builder.addBeanClass(io.vidocq.humboldt.cdi.WithSpanInterceptor.class);
            for (Class<?> bean : beanClasses) {
                builder.addBeanClass(bean);
            }
            this.container = builder.build();

            // Parse MP Config (otel.* + mp_telemetry.*) from the WAR.
            Map<String, String> mpProps = parseMicroprofileConfigProperties(archive);

            // OTel SDK autoconfigure SPI providers the archive declares in META-INF/services (the TCK's
            // in-memory span/metric exporters, samplers, propagators, resources, customizers): discovered and
            // bridged to the Humboldt SDK by humboldt-otel-interop, as the Vidocq runtime does. The deployment
            // class loader exposes the archive's own declarations only.
            OtelSpiAutoConfiguration.Result spi = OtelSpiAutoConfiguration.discover(
                    mpPropsToOtelEnv(mpProps),
                    new DeploymentServicesClassLoader(archive, Thread.currentThread().getContextClassLoader()));

            this.humboldt = HumboldtAutoConfigure.configure(
                    EnvConfig.of(spi.env(), Map.of()),
                    spi.extraSpanExporters(),
                    spi.samplerOverride(),
                    spi.propagatorsOverride(),
                    spi.extraMetricExporters());
            GlobalOpenTelemetry.set(this.humboldt);

            // M7c.2: if the WAR contains JAX-RS resources, start
            // Cassini on Chappe. Wire humboldt-rest filters so that
            // SERVER spans are generated as required by the TCK.
            ProtocolMetaData metaData = new ProtocolMetaData();
            List<Class<?>> resourceClasses = beanClasses.stream()
                    .filter(c -> c.isAnnotationPresent(Path.class))
                    .toList();
            List<Class<?>> providerClasses = beanClasses.stream()
                    .filter(c -> c.isAnnotationPresent(Provider.class)
                            && !c.isAnnotationPresent(Path.class))
                    .toList();
            if (!resourceClasses.isEmpty()) {
                String ctxName = deriveContextName(archive);
                String contextPath = ctxName.isEmpty() ? "/" : "/" + ctxName;
                CassiniHarness.Builder hb = CassiniHarness.builder().contextPath(contextPath);
                for (Class<?> r : resourceClasses) hb.resourceClass(r);
                // humboldt-rest filters to generate SERVER spans
                hb.provider(new HumboldtServerRequestFilter());
                hb.provider(new HumboldtServerResponseFilter());
                hb.provider(new HumboldtSpanFinalizer());
                // HBT-2 — activates the Vauban RequestContext around each HTTP dispatch.
                // CassiniHarness builds its pipeline without going through CassiniStackBuilder,
                // so the auto-injected filter from VaubanBeanProvider.getResourceClasses()
                // is not seen — register it manually here. For production
                // (normal CassiniStack), no user action is required.
                hb.provider(new io.vidocq.cassini.cdi.vauban.VaubanRequestScopeFilter(this.container));
                for (Class<?> p : providerClasses) {
                    try { hb.provider(p.getDeclaredConstructor().newInstance()); }
                    catch (ReflectiveOperationException e) {
                        LOG.log(Level.WARNING, "  ⚠ provider non-instantiable : {0}", p.getName());
                    }
                }
                this.cassini = hb.start();
                LOG.log(Level.INFO, "  -> Cassini started on {0} ({1} resources, {2} providers)",
                        cassini.baseUrl(), resourceClasses.size(), providerClasses.size());

                HTTPContext httpContext = new HTTPContext("127.0.0.1", cassini.port());
                httpContext.add(new Servlet("ArquillianServletRunner", contextPath));
                metaData.addContext(httpContext);
            }
            return metaData;
        } catch (Exception e) {
            throw new DeploymentException("Failed to deploy " + archive.getName(), e);
        }
    }

    private static String deriveContextName(Archive<?> archive) {
        String name = archive.getName();
        if (name == null) return "";
        if (name.endsWith(".war")) name = name.substring(0, name.length() - 4);
        if (name.endsWith(".jar")) name = name.substring(0, name.length() - 4);
        return name;
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        LOG.log(Level.INFO, "Humboldt Arquillian container — undeploy {0}", archive.getName());
        try {
            if (cassini != null) {
                cassini.close();
                cassini = null;
            }
        } finally {
            try {
                if (container != null) {
                    container.close();
                    container = null;
                }
            } finally {
                try {
                    if (humboldt != null) {
                        humboldt.close();
                        humboldt = null;
                    }
                } finally {
                    GlobalOpenTelemetry.resetForTest();
                }
            }
        }
    }

    /**
     * Reads {@code META-INF/microprofile-config.properties} from the ShrinkWrap WAR
     * (ShrinkWrap locations: {@code /META-INF/} for JavaArchive,
     * {@code /WEB-INF/classes/META-INF/} for WebArchive). Returns an empty map
     * if the file does not exist.
     */
    private static Map<String, String> parseMicroprofileConfigProperties(Archive<?> archive) {
        Node node = archive.get("/META-INF/microprofile-config.properties");
        if (node == null) {
            node = archive.get("/WEB-INF/classes/META-INF/microprofile-config.properties");
        }
        if (node == null || node.getAsset() == null) return new HashMap<>();
        try (InputStream in = node.getAsset().openStream()) {
            Properties props = new Properties();
            props.load(in);
            Map<String, String> out = new HashMap<>();
            for (String name : props.stringPropertyNames()) {
                out.put(name, props.getProperty(name));
            }
            return out;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error reading microprofile-config.properties: {0}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Converts MP Config properties ({@code lowercase.dotted}) into OTel environment
     * variables ({@code SCREAMING_SNAKE_CASE}). As required by the OTel spec:
     * {@code otel.traces.exporter} ↔ {@code OTEL_TRACES_EXPORTER}.
     */
    private static Map<String, String> mpPropsToOtelEnv(Map<String, String> mpProps) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : mpProps.entrySet()) {
            String envKey = e.getKey().replace('.', '_').replace('-', '_').toUpperCase();
            out.put(envKey, e.getValue());
        }
        return out;
    }

    /**
     * Extracts classes from the ShrinkWrap WAR. Assumes they can be loaded
     * through the current ClassLoader — true in Local Arquillian mode (same JVM
     * as the test spec, so classes annotated with {@code @Deployment}
     * were already loaded by the test ClassLoader).
     */
    private static List<Class<?>> extractBeanClasses(Archive<?> archive) {
        List<Class<?>> classes = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (ArchivePath p : archive.getContent().keySet()) {
            String path = p.get();
            String s = path.startsWith("/") ? path.substring(1) : path;
            if (s.startsWith(WEB_INF_CLASSES_PREFIX)) {
                s = s.substring(WEB_INF_CLASSES_PREFIX.length());
            }
            if (!s.endsWith(".class")) continue;
            String fqn = s.substring(0, s.length() - ".class".length()).replace('/', '.');
            try {
                classes.add(Class.forName(fqn, true, cl));
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                LOG.log(Level.WARNING, "  ⚠ class ignored ({0}): {1}",
                        fqn, e.getMessage());
            }
        }
        return classes;
    }
}

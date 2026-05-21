package io.vidocq.humboldt.tck.arquillian;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider;
import io.vidocq.humboldt.runtime.AutoConfiguredHumboldt;
import io.vidocq.humboldt.runtime.EnvConfig;
import io.vidocq.humboldt.runtime.HumboldtAutoConfigure;
import io.vidocq.humboldt.sdk.trace.export.SpanExporter;
import io.vidocq.humboldt.tck.bridge.OtelSpanExporterBridge;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Node;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Container Arquillian "embedded" Humboldt — assemble Vauban CDI Lite +
 * (futur Cassini JAX-RS / Chappe HTTP) + humboldt-runtime in-process pour
 * exécuter le TCK MicroProfile Telemetry 2.1.
 *
 * <p>Approche from-scratch (cf. {@code tasks/m7b-architecture-analysis.md}
 * Option C) — pas de réutilisation de vidocq-mps pour éviter le cycle de
 * dépendance humboldt-tck → vidocq-mps → humboldt.</p>
 *
 * <p>Progression incrémentale :</p>
 * <ul>
 *   <li><strong>M7b.4b.1</strong> ✅ Squelette : lifecycle start/stop, deploy NoOp</li>
 *   <li><strong>M7b.4b.2</strong> ✅ Deploy : boot Vauban CDI sur les classes
 *       du war + AutoConfiguredHumboldt avec env config hardcodée</li>
 *   <li><strong>M7b.4b.3</strong> Bridge OTel SDK autoconfigure (parse
 *       microprofile-config.properties + scan ServiceLoader OTel)</li>
 *   <li><strong>M7b.4b.4</strong> TestEnricher CDI pour injection
 *       {@code @Inject} dans la classe de test</li>
 * </ul>
 *
 * <p>Protocole Arquillian utilisé : <em>Local</em> — tests exécutés dans la
 * même JVM que le container. Le test peut donc accéder au CDI via
 * {@link VaubanContainer#current()} en attendant l'enricher M7b.4b.4.</p>
 */
public class HumboldtDeployableContainer implements DeployableContainer<HumboldtContainerConfig> {

    private static final Logger LOG = System.getLogger(HumboldtDeployableContainer.class.getName());

    /** Préfixe interne ShrinkWrap pour les classes d'un {@code WebArchive}. */
    private static final String WEB_INF_CLASSES_PREFIX = "WEB-INF/classes/";

    private VaubanContainer container;
    private AutoConfiguredHumboldt humboldt;

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
            for (Class<?> bean : beanClasses) {
                builder.addBeanClass(bean);
            }
            this.container = builder.build();

            // Parse MP Config (otel.* + mp_telemetry.*) du war.
            Map<String, String> mpProps = parseMicroprofileConfigProperties(archive);

            // Charge les providers OTel SDK autoconfigure déclarés via
            // META-INF/services dans le war (pattern d'extension TCK).
            Map<String, ConfigurableSpanExporterProvider> spanExporterProviders =
                    loadConfigurableSpanExporterProviders(archive);

            // Si un provider correspond au nom 'otel.traces.exporter', on crée son
            // OTel SpanExporter et on le bridge vers Humboldt — c'est le mécanisme
            // que les TCK utilisent pour récupérer leur InMemorySpanExporter.
            List<SpanExporter> extraSpanExporters = new ArrayList<>();
            String tracesExporterName = mpProps.get("otel.traces.exporter");
            if (tracesExporterName != null && spanExporterProviders.containsKey(tracesExporterName)) {
                ConfigurableSpanExporterProvider p = spanExporterProviders.get(tracesExporterName);
                var otelExporter = p.createExporter(new MapConfigProperties(mpProps));
                extraSpanExporters.add(new OtelSpanExporterBridge(otelExporter));
                LOG.log(Level.INFO, "  → OTel SpanExporter '{0}' bridgé vers Humboldt", tracesExporterName);
            }

            // Construit l'EnvConfig Humboldt — convertit les props MP (lowercase.dotted)
            // vers les env vars OTEL (SCREAMING_SNAKE) attendues par EnvConfig.
            // Si un bridge externe est en place, force OTEL_TRACES_EXPORTER=none
            // pour éviter qu'Humboldt ajoute son propre InMemorySpanExporter natif.
            Map<String, String> envMap = mpPropsToOtelEnv(mpProps);
            envMap.putIfAbsent("OTEL_TRACES_SAMPLER", "always_on");
            envMap.putIfAbsent("OTEL_METRICS_EXPORTER", "none");
            envMap.putIfAbsent("OTEL_LOGS_EXPORTER", "none");
            if (!extraSpanExporters.isEmpty()) {
                envMap.put("OTEL_TRACES_EXPORTER", "none");
            } else {
                envMap.putIfAbsent("OTEL_TRACES_EXPORTER", "none");
            }

            this.humboldt = HumboldtAutoConfigure.configure(
                    EnvConfig.of(envMap, Map.of()),
                    extraSpanExporters);
            GlobalOpenTelemetry.set(this.humboldt);

            return new ProtocolMetaData();
        } catch (Exception e) {
            throw new DeploymentException("Failed to deploy " + archive.getName(), e);
        }
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        LOG.log(Level.INFO, "Humboldt Arquillian container — undeploy {0}", archive.getName());
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

    /**
     * Lit {@code META-INF/microprofile-config.properties} du war ShrinkWrap
     * (positions ShrinkWrap : {@code /META-INF/} pour JavaArchive,
     * {@code /WEB-INF/classes/META-INF/} pour WebArchive). Retourne une map
     * vide si le fichier n'existe pas.
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
            LOG.log(Level.WARNING, "Erreur lecture microprofile-config.properties : {0}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Charge les {@link ConfigurableSpanExporterProvider} déclarés dans le war
     * ShrinkWrap via {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider}.
     * Retourne une map {@code nom → instance} indexée par {@code provider.getName()}.
     *
     * <p>Pattern utilisé par les TCK MP Telemetry (cf. décompilation
     * {@code ExporterSpiTest.createDeployment()}) qui ajoutent leur
     * {@code InMemorySpanExporterProvider} via
     * {@code WebArchive.addAsServiceProvider(ConfigurableSpanExporterProvider.class, ...)}.</p>
     */
    private static Map<String, ConfigurableSpanExporterProvider> loadConfigurableSpanExporterProviders(Archive<?> archive) {
        String service = "io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider";
        Node node = archive.get("/META-INF/services/" + service);
        if (node == null) {
            node = archive.get("/WEB-INF/classes/META-INF/services/" + service);
        }
        if (node == null || node.getAsset() == null) return Map.of();

        Map<String, ConfigurableSpanExporterProvider> out = new LinkedHashMap<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = node.getAsset().openStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String fqn = line.trim();
                if (fqn.isEmpty() || fqn.startsWith("#")) continue;
                try {
                    Class<?> cls = Class.forName(fqn, true, cl);
                    ConfigurableSpanExporterProvider p = (ConfigurableSpanExporterProvider)
                            cls.getDeclaredConstructor().newInstance();
                    out.put(p.getName(), p);
                    LOG.log(Level.INFO, "  → SpanExporterProvider chargé : {0} (name={1})",
                            fqn, p.getName());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "  ⚠ provider ignoré ({0}) : {1}", fqn, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Erreur lecture services/{0} : {1}", service, e.getMessage());
        }
        return out;
    }

    /**
     * Convertit les propriétés MP Config ({@code lowercase.dotted}) en variables
     * d'environnement OTel ({@code SCREAMING_SNAKE_CASE}). Conformément à la spec
     * OTel : {@code otel.traces.exporter} ↔ {@code OTEL_TRACES_EXPORTER}.
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
     * Extrait les classes du war ShrinkWrap. Suppose qu'elles sont chargeables
     * via le ClassLoader courant — vrai en mode Local Arquillian (même JVM
     * que la spec de test, donc les classes annotées {@code @Deployment}
     * étaient déjà chargées par le ClassLoader de test).
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
                LOG.log(Level.WARNING, "  ⚠ classe ignorée ({0}) : {1}",
                        fqn, e.getMessage());
            }
        }
        return classes;
    }
}

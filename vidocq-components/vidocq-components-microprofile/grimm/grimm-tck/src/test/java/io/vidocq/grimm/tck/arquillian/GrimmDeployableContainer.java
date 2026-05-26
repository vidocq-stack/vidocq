package io.vidocq.grimm.tck.arquillian;

import io.vidocq.grimm.cdi.TckDeploymentContext;
import io.vidocq.grimm.internal.reader.StaticFileReader;
import io.vidocq.runtime.core.VidocqBootstrap;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Embedded Arquillian container for Grimm OpenAPI TCK runs.
 *
 * It is a focused replacement for the generic runtime container, with a corrected
 * class-name extraction for ShrinkWrap web archives (WEB-INF/classes prefix).
 */
public class GrimmDeployableContainer implements DeployableContainer<GrimmContainerConfiguration> {

    private GrimmContainerConfiguration config;
    private VidocqBootstrap bootstrap;
    private int actualPort;

    @Override
    public Class<GrimmContainerConfiguration> getConfigurationClass() {
        return GrimmContainerConfiguration.class;
    }

    @Override
    public void setup(GrimmContainerConfiguration configuration) {
        this.config = configuration;
    }

    @Override
    public void start() throws LifecycleException {
        // Per-deployment bootstrap happens in deploy().
    }

    @Override
    public void stop() throws LifecycleException {
        if (bootstrap != null) {
            bootstrap.shutdown();
            bootstrap = null;
        }
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 6.0");
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        try {
            String host = resolveString("grimm.tck.host", config.getHost());
            int configuredPort = resolveInt("grimm.tck.port", config.getPort());
            boolean waitForReadiness = resolveBoolean("grimm.tck.waitForReadiness", config.isWaitForReadiness());
            long readinessTimeoutMillis = resolveLong("grimm.tck.readinessTimeoutMillis", config.getReadinessTimeoutMillis());
            String readinessPath = resolveString("grimm.tck.readinessPath", config.getReadinessPath());
            String systemProperties = resolveString("grimm.tck.systemProperties", config.getSystemProperties());

            actualPort = configuredPort == 0 ? findFreePort() : configuredPort;
            List<String> classNames = extractClassNames(archive);
            addRequiredRuntimeClasses(classNames);
            TckDeploymentContext.setDiscoveredTypes(resolveScanClasses(classNames));
            TckDeploymentContext.setConfig(extractMicroProfileConfig(archive));
            installStaticDocumentOverride(archive);

            applyBootstrapSystemProperties(host, actualPort, systemProperties);

            bootstrap = VidocqBootstrap.create();
            bootstrap.configure(classNames);
            bootstrap.start();
            if (waitForReadiness) {
                waitForReadiness(host, actualPort, readinessPath, readinessTimeoutMillis);
            }
            dumpOpenApiIfRequested(host, actualPort);

            HTTPContext ctx = new HTTPContext(host, actualPort);
            ctx.add(new Servlet("default", "/"));
            ProtocolMetaData meta = new ProtocolMetaData();
            meta.addContext(ctx);
            return meta;
        } catch (Exception e) {
            throw new DeploymentException("Failed to deploy Grimm TCK application", e);
        }
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        if (bootstrap != null) {
            bootstrap.shutdown();
            bootstrap = null;
        }
        TckDeploymentContext.clear();
        StaticFileReader.clearExplicitDocument();
        clearBootstrapSystemProperties(resolveString("grimm.tck.systemProperties", config.getSystemProperties()));
    }

    @Override
    public void deploy(Descriptor descriptor) {
        // no-op
    }

    @Override
    public void undeploy(Descriptor descriptor) {
        // no-op
    }

    private List<String> extractClassNames(Archive<?> archive) {
        List<String> out = new ArrayList<>();
        Map<ArchivePath, org.jboss.shrinkwrap.api.Node> content = archive.getContent();
        for (Map.Entry<ArchivePath, org.jboss.shrinkwrap.api.Node> e : content.entrySet()) {
            String path = e.getKey().get();
            if (!path.endsWith(".class") || path.contains("module-info")) {
                continue;
            }
            String normalized = path;
            if (normalized.startsWith("/WEB-INF/classes/")) {
                normalized = normalized.substring("/WEB-INF/classes/".length());
            } else if (normalized.startsWith("WEB-INF/classes/")) {
                normalized = normalized.substring("WEB-INF/classes/".length());
            } else if (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            String className = normalized.replace('/', '.').replace(".class", "");
            out.add(className);
        }
        return out;
    }

    private Map<String, String> extractMicroProfileConfig(Archive<?> archive) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<ArchivePath, org.jboss.shrinkwrap.api.Node> content = archive.getContent();
        for (Map.Entry<ArchivePath, org.jboss.shrinkwrap.api.Node> entry : content.entrySet()) {
            String path = entry.getKey().get();
            if (path == null || !path.endsWith("microprofile-config.properties")) {
                continue;
            }
            org.jboss.shrinkwrap.api.Node node = entry.getValue();
            if (node == null || node.getAsset() == null) {
                continue;
            }
            try (InputStream stream = node.getAsset().openStream()) {
                Properties properties = new Properties();
                properties.load(stream);
                for (String key : properties.stringPropertyNames()) {
                    out.put(key, properties.getProperty(key));
                }
            } catch (Exception ignored) {
                // Keep going: partial config is still better than none.
            }
        }
        return out;
    }

    private void installStaticDocumentOverride(Archive<?> archive) {
        String[] candidates = {
                "/META-INF/openapi.yaml",
                "/META-INF/openapi.yml",
                "/META-INF/openapi.json",
                "/WEB-INF/classes/META-INF/openapi.yaml",
                "/WEB-INF/classes/META-INF/openapi.yml",
                "/WEB-INF/classes/META-INF/openapi.json"
        };
        Map<ArchivePath, org.jboss.shrinkwrap.api.Node> content = archive.getContent();
        for (Map.Entry<ArchivePath, org.jboss.shrinkwrap.api.Node> entry : content.entrySet()) {
            String path = entry.getKey().get();
            if (path == null) continue;
            for (String candidate : candidates) {
                if (path.equals(candidate)) {
                    org.jboss.shrinkwrap.api.Node node = entry.getValue();
                    if (node == null || node.getAsset() == null) continue;
                    try (InputStream stream = node.getAsset().openStream()) {
                        String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                        String logical = candidate.substring(candidate.lastIndexOf("META-INF/"));
                        StaticFileReader.setExplicitDocument(logical, body);
                        return;
                    } catch (IOException ignored) {
                        // try next
                    }
                }
            }
        }
        // No static document in this deployment — make sure no stale override remains.
        StaticFileReader.clearExplicitDocument();
    }

    private List<Class<?>> resolveScanClasses(List<String> classNames) {
        List<Class<?>> out = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = GrimmDeployableContainer.class.getClassLoader();
        }
        for (String className : classNames) {
            if (className.startsWith("io.vidocq.grimm.")) {
                continue;
            }
            try {
                Class<?> type = Class.forName(className, false, cl);
                out.add(type);
            } catch (ClassNotFoundException ignored) {
                // Ignore classes not resolvable in current test classloader.
            }
        }
        return out;
    }

    private void addRequiredRuntimeClasses(List<String> classNames) {
        addIfPresent(classNames, "io.vidocq.grimm.cdi.TckGrimmSupportProducer");
        addIfPresent(classNames, "io.vidocq.grimm.cdi.GrimmModelCache");
        addIfPresent(classNames, "io.vidocq.grimm.cdi.OpenApiResource");
    }

    private void addIfPresent(List<String> classNames, String fqcn) {
        try {
            Class.forName(fqcn, false, Thread.currentThread().getContextClassLoader());
            if (!classNames.contains(fqcn)) {
                classNames.add(fqcn);
            }
        } catch (ClassNotFoundException ignored) {
            // Optional dependency for harness bootstrap; ignore if unavailable.
        }
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception ignored) {
            return 18080;
        }
    }

    private void applyBootstrapSystemProperties(String host, int port, String systemProperties) {
        System.setProperty("test.url", "http://" + host + ":" + port);
        System.setProperty("vidocq.chappe.listener.default.host", host);
        System.setProperty("vidocq.chappe.listener.default.port", String.valueOf(port));

        String configuredPairs = Objects.requireNonNullElse(systemProperties, "").trim();
        if (configuredPairs.isEmpty()) {
            return;
        }
        for (String pair : configuredPairs.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0 || separator == trimmed.length() - 1) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            if (!key.isEmpty()) {
                System.setProperty(key, value);
            }
        }
    }

    private void clearBootstrapSystemProperties(String systemProperties) {
        System.clearProperty("test.url");
        System.clearProperty("vidocq.chappe.listener.default.host");
        System.clearProperty("vidocq.chappe.listener.default.port");

        String configuredPairs = Objects.requireNonNullElse(systemProperties, "").trim();
        if (configuredPairs.isEmpty()) {
            return;
        }
        for (String pair : configuredPairs.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            if (!key.isEmpty()) {
                System.clearProperty(key);
            }
        }
    }

    private void waitForReadiness(String host, int port, String path, long timeoutMillis) throws DeploymentException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        URI uri = URI.create("http://" + host + ":" + port + path);
        Exception lastError = null;

        while (System.nanoTime() < deadline) {
            try {
                HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(500);
                connection.setReadTimeout(500);
                int code = connection.getResponseCode();
                if (code > 0) {
                    return;
                }
            } catch (IOException ioe) {
                lastError = ioe;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new DeploymentException("Interrupted while waiting for readiness", ie);
            }
        }

        throw new DeploymentException("Readiness timeout after " + timeoutMillis + "ms on " + uri, lastError);
    }

    private String resolveString(String key, String defaultValue) {
        String value = System.getProperty(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private int resolveInt(String key, int defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }

    private long resolveLong(String key, long defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(value.trim());
    }

    private boolean resolveBoolean(String key, boolean defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private void dumpOpenApiIfRequested(String host, int port) {
        String target = System.getProperty("grimm.tck.dumpOpenApiPath");
        if (target == null || target.isBlank()) {
            return;
        }
        URI uri = URI.create("http://" + host + ":" + port + "/openapi?format=json");
        try {
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return;
            }
            try (InputStream in = connection.getInputStream()) {
                String payload = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Files.writeString(Path.of(target), payload, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // Debug helper only: never break deployment for a failed dump.
        }
    }
}





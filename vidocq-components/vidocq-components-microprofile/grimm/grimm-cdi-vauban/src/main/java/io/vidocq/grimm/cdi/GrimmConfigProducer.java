package io.vidocq.grimm.cdi;

import io.vidocq.grimm.internal.config.GrimmConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * CDI producer that exposes the immutable {@link GrimmConfig} snapshot as an injectable
 * bean.
 *
 * <p>Reads the MicroProfile {@link Config} via {@link ConfigProvider#getConfig()} at
 * application-scoped instantiation. In Vidocq deployments this resolves to the Ravel
 * MP-Config provider.</p>
 */
@Dependent
public class GrimmConfigProducer {

    @Produces
    @ApplicationScoped
    public GrimmConfig produceGrimmConfig() {
        try {
            Config config = ConfigProvider.getConfig();
            return GrimmConfig.fromMpConfig(config);
        } catch (IllegalStateException | java.util.ServiceConfigurationError e) {
            // No MP-Config provider on the classpath — fall back to defaults so the
            // pipeline still produces a valid (empty) /openapi document.
            return GrimmConfig.defaults();
        }
    }

    /** Bridges the BCE-discovered {@code @Path} classes into the CDI runtime. */
    @Produces
    @ApplicationScoped
    public ScannedTypes produceScannedTypes() {
        return ScannedTypes.of(GrimmExtension.discoveredTypes());
    }
}



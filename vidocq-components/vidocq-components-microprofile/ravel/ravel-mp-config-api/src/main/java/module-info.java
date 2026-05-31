/**
 * Explicit module descriptor for MicroProfile Config 3.1.
 *
 * <p>The official artifact
 * {@code org.eclipse.microprofile.config:microprofile-config-api} only declares
 * {@code Automatic-Module-Name} in its manifest. This module-info turns it into
 * an explicit module without modifying spec classes, while keeping the exact
 * same module name ({@code org.eclipse.microprofile.config}) so existing
 * {@code requires} statements keep working.
 *
 * <p>The {@code uses ConfigProviderResolver} declaration mirrors the mechanism
 * used by {@code ConfigProvider#getConfig()} through {@link java.util.ServiceLoader}.
 */
module org.eclipse.microprofile.config {
    exports org.eclipse.microprofile.config;
    exports org.eclipse.microprofile.config.inject;
    exports org.eclipse.microprofile.config.spi;

    uses org.eclipse.microprofile.config.spi.ConfigProviderResolver;
}


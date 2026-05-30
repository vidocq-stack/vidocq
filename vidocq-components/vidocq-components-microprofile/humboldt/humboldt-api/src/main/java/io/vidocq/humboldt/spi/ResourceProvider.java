package io.vidocq.humboldt.spi;

/**
 * SPI for {@code Resource} attributes — discovered via {@link java.util.ServiceLoader}.
 *
 * <p>M0 stub — will provide OTel Resource attributes (service.name, host.name, etc.)
 * in M2.</p>
 */
public interface ResourceProvider {

    /**
     * @return the provider identifier (for example {@code "host"}, {@code "process"},
     *         {@code "container"}).
     */
    String id();
}

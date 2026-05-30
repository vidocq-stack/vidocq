package io.vidocq.humboldt.spi;

/**
 * SPI for samplers — discovered via {@link java.util.ServiceLoader}.
 *
 * <p>M0 stub — the implementation (always_on / always_off / parentbased / traceidratio)
 * arrives with {@code humboldt-sdk-trace} in M2.</p>
 */
public interface SamplerProvider {

    /**
     * @return the logical sampler name, matching {@code OTEL_TRACES_SAMPLER}.
     */
    String name();
}

package io.vidocq.humboldt.spi;

/**
 * SPI samplers — découvert via {@link java.util.ServiceLoader}.
 *
 * <p>Stub M0 — l'implémentation (always_on / always_off / parentbased / traceidratio)
 * arrive avec {@code humboldt-sdk-trace} en M2.</p>
 */
public interface SamplerProvider {

    /**
     * @return le nom logique du sampler, matche {@code OTEL_TRACES_SAMPLER}.
     */
    String name();
}

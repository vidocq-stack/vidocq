package io.vidocq.runtime.spi.config;

/**
 * Raw string to target type converter.
 * <p>
 * Implementations are saved in the implementation layer of
 * {@link VidocqConfig}. The API is aligned with {@code org.eclipse.microprofile.config.spi.Converter}.
 * </p>
 *
 * <p><b>Raw string to target type converter.</b>
 * API aligned with MicroProfile Config {@code Converter}.</p>
 *
 * @param <T> target type / target type
 */
@FunctionalInterface
public interface Converter<T> {

    /**
     * Converts the raw value. May throw {@link IllegalArgumentException} if invalid.
     * Returning {@code null} means "empty value".
     * <p>Convert the raw value. May throw {@link IllegalArgumentException} if invalid.
     * Returning {@code null} means "empty value".</p>
     */
    T convert(String value);
}

package fr.vidocq.vidocq.ext.rest.cassini.internal.context;

import fr.vidocq.vidocq.ext.rest.cassini.internal.ExceptionMapperRegistry;
import fr.vidocq.vidocq.ext.rest.cassini.internal.MessageBodyRegistry;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * Implémentation {@link Providers} §10.2 — façade sur les registries
 * internes Cassini pour les providers MBR/MBW et ExceptionMapper.
 * Les ContextResolver ne sont pas encore enregistrés (getContextResolver
 * retourne null).
 */
public final class CassiniProviders implements Providers {

    private final MessageBodyRegistry bodies;
    private final ExceptionMapperRegistry exceptionMappers;

    public CassiniProviders(MessageBodyRegistry bodies, ExceptionMapperRegistry exceptionMappers) {
        this.bodies = bodies;
        this.exceptionMappers = exceptionMappers;
    }

    @Override
    public <T> MessageBodyReader<T> getMessageBodyReader(Class<T> type, Type genericType,
                                                         Annotation[] annotations, MediaType mediaType) {
        return bodies.<T>findReader(type, genericType, annotations, mediaType).orElse(null);
    }

    @Override
    public <T> MessageBodyWriter<T> getMessageBodyWriter(Class<T> type, Type genericType,
                                                         Annotation[] annotations, MediaType mediaType) {
        return bodies.<T>findWriter(type, genericType, annotations, mediaType).orElse(null);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public <T extends Throwable> ExceptionMapper<T> getExceptionMapper(Class<T> type) {
        try {
            Throwable dummy = type.getDeclaredConstructor().newInstance();
            return exceptionMappers.map(dummy).map(r -> (ExceptionMapper<T>) null).orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public <T> ContextResolver<T> getContextResolver(Class<T> contextType, MediaType mediaType) {
        return null;
    }
}

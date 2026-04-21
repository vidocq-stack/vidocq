package fr.vidocq.vidocq.ext.rest.cassini.internal.filter;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registre des {@link ContainerRequestFilter} / {@link ContainerResponseFilter}
 * découverts par scan CDI. Expose les listes déjà triées par priorité.
 *
 * <p>§6.2 : les request filters s'exécutent dans l'ordre de priorité croissant ;
 * les response filters dans l'ordre décroissant.</p>
 */
public final class FilterRegistry {

    private static final AnnotationLiteral<Any> ANY = new AnnotationLiteral<Any>() {};

    private final List<FilterEntry<ContainerRequestFilter>> requestFilters = new ArrayList<>();
    private final List<FilterEntry<ContainerResponseFilter>> responseFilters = new ArrayList<>();
    private final List<FilterEntry<ReaderInterceptor>> readerInterceptors = new ArrayList<>();
    private final List<FilterEntry<WriterInterceptor>> writerInterceptors = new ArrayList<>();
    private final List<ContextResolver<?>> contextResolvers = new ArrayList<>();

    public void addRequest(ContainerRequestFilter filter) {
        requestFilters.add(FilterEntry.of(filter));
        requestFilters.sort(Comparator.comparingInt(FilterEntry::priority));
    }

    public void addResponse(ContainerResponseFilter filter) {
        responseFilters.add(FilterEntry.of(filter));
        responseFilters.sort(Comparator.comparingInt(FilterEntry<ContainerResponseFilter>::priority).reversed());
    }

    /** Enregistre un filtre d'après son type, sans distinction request/response.
     *  Si l'instance implémente les deux, elle est ajoutée aux deux listes. */
    public void register(Object instance) {
        if (instance instanceof ContainerRequestFilter r) addRequest(r);
        if (instance instanceof ContainerResponseFilter r) addResponse(r);
        if (instance instanceof ReaderInterceptor r) addReaderInterceptor(r);
        if (instance instanceof WriterInterceptor r) addWriterInterceptor(r);
        if (instance instanceof ContextResolver<?> r) addContextResolver(r);
    }

    public void addContextResolver(ContextResolver<?> r) { contextResolvers.add(r); }
    public List<ContextResolver<?>> contextResolvers() { return contextResolvers; }

    public void addReaderInterceptor(ReaderInterceptor i) {
        readerInterceptors.add(FilterEntry.of(i));
        readerInterceptors.sort(Comparator.comparingInt(FilterEntry::priority));
    }

    public void addWriterInterceptor(WriterInterceptor i) {
        writerInterceptors.add(FilterEntry.of(i));
        writerInterceptors.sort(Comparator.comparingInt(FilterEntry::priority));
    }

    public List<FilterEntry<ReaderInterceptor>> readerInterceptors() { return readerInterceptors; }
    public List<FilterEntry<WriterInterceptor>> writerInterceptors() { return writerInterceptors; }

    public List<FilterEntry<ContainerRequestFilter>> requestFilters() { return requestFilters; }
    public List<FilterEntry<ContainerResponseFilter>> responseFilters() { return responseFilters; }

    public List<FilterEntry<ContainerRequestFilter>> preMatching() {
        return requestFilters.stream().filter(FilterEntry::preMatching).toList();
    }

    public List<FilterEntry<ContainerRequestFilter>> postMatching() {
        return requestFilters.stream().filter(f -> !f.preMatching()).toList();
    }

    public static FilterRegistry discover(BeanManager bm) {
        FilterRegistry reg = new FilterRegistry();
        if (bm == null) return reg;
        Map<Class<?>, Boolean> seen = new HashMap<>();
        for (Bean<?> bean : bm.getBeans(Object.class, ANY)) {
            Class<?> cls = bean.getBeanClass();
            if (seen.putIfAbsent(cls, Boolean.TRUE) != null) continue;
            if (cls.getAnnotation(Provider.class) == null) continue;
            Object instance = null;
            if (ContainerRequestFilter.class.isAssignableFrom(cls)) {
                instance = bm.getReference(bean, cls, bm.createCreationalContext(bean));
                reg.addRequest((ContainerRequestFilter) instance);
            }
            if (ContainerResponseFilter.class.isAssignableFrom(cls)) {
                if (instance == null) instance = bm.getReference(bean, cls, bm.createCreationalContext(bean));
                reg.addResponse((ContainerResponseFilter) instance);
            }
            if (ReaderInterceptor.class.isAssignableFrom(cls)) {
                if (instance == null) instance = bm.getReference(bean, cls, bm.createCreationalContext(bean));
                reg.addReaderInterceptor((ReaderInterceptor) instance);
            }
            if (WriterInterceptor.class.isAssignableFrom(cls)) {
                if (instance == null) instance = bm.getReference(bean, cls, bm.createCreationalContext(bean));
                reg.addWriterInterceptor((WriterInterceptor) instance);
            }
            if (ContextResolver.class.isAssignableFrom(cls)) {
                if (instance == null) instance = bm.getReference(bean, cls, bm.createCreationalContext(bean));
                reg.addContextResolver((ContextResolver<?>) instance);
            }
        }
        return reg;
    }
}

package fr.vidocq.vidocq.ext.rest.cassini.internal;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registre des {@link ExceptionMapper} découverts parmi les beans CDI
 * annotés {@code @Provider}.
 *
 * <p>Sélection du mapper (§4.4) : on préfère le mapper dont le type
 * paramétré d'exception est le plus spécifique (plus proche de la
 * classe concrète) parmi ceux qui matchent.</p>
 */
public final class ExceptionMapperRegistry {

    private static final AnnotationLiteral<Any> ANY = new AnnotationLiteral<Any>() {};

    private final List<Registration<?>> mappers = new ArrayList<>();

    public record Registration<T extends Throwable>(Class<T> exceptionType, ExceptionMapper<T> mapper) {}

    public <T extends Throwable> void register(Class<T> exceptionType, ExceptionMapper<T> mapper) {
        mappers.add(new Registration<>(exceptionType, mapper));
    }

    /** Scanne le BeanManager à la recherche de beans {@code @Provider} implémentant ExceptionMapper. */
    public static ExceptionMapperRegistry discover(BeanManager bm) {
        ExceptionMapperRegistry reg = new ExceptionMapperRegistry();
        if (bm == null) return reg;
        Map<Class<?>, Boolean> seen = new HashMap<>();
        for (Bean<?> bean : bm.getBeans(Object.class, ANY)) {
            Class<?> cls = bean.getBeanClass();
            if (seen.putIfAbsent(cls, Boolean.TRUE) != null) continue;
            if (cls.getAnnotation(Provider.class) == null) continue;
            if (!ExceptionMapper.class.isAssignableFrom(cls)) continue;
            Class<? extends Throwable> excType = resolveExceptionType(cls);
            if (excType == null) continue;
            Object instance = bm.getReference(bean, cls, bm.createCreationalContext(bean));
            @SuppressWarnings({"unchecked", "rawtypes"})
            Registration<? extends Throwable> r = new Registration(excType, (ExceptionMapper) instance);
            reg.mappers.add(r);
        }
        return reg;
    }

    public Optional<Response> map(Throwable t) {
        Registration<?> best = null;
        for (Registration<?> r : mappers) {
            if (r.exceptionType().isInstance(t)) {
                if (best == null || best.exceptionType().isAssignableFrom(r.exceptionType())) {
                    best = r;
                }
            }
        }
        if (best == null) return Optional.empty();
        @SuppressWarnings({"rawtypes", "unchecked"})
        Response r = ((ExceptionMapper) best.mapper()).toResponse(t);
        return Optional.ofNullable(r);
    }

    public int size() { return mappers.size(); }

    @SuppressWarnings("unchecked")
    private static Class<? extends Throwable> resolveExceptionType(Class<?> mapperClass) {
        for (Type iface : mapperClass.getGenericInterfaces()) {
            if (iface instanceof ParameterizedType pt
                    && pt.getRawType() == ExceptionMapper.class
                    && pt.getActualTypeArguments().length == 1
                    && pt.getActualTypeArguments()[0] instanceof Class<?> c
                    && Throwable.class.isAssignableFrom(c)) {
                return (Class<? extends Throwable>) c;
            }
        }
        Class<?> sup = mapperClass.getSuperclass();
        return sup == null || sup == Object.class ? null : resolveExceptionType(sup);
    }
}

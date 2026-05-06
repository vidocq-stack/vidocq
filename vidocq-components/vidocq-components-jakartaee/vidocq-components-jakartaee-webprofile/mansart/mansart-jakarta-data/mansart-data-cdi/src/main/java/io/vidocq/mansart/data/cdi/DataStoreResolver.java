package io.vidocq.mansart.data.cdi;

import io.vidocq.mansart.data.core.MansartData;
import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import jakarta.data.repository.Repository;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import javax.sql.XADataSource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves the {@link RepositoryRuntime} bound to a given {@code @Repository}'s {@code dataStore}.
 *
 * <p>The Jakarta Data 1.0 spec defines {@link Repository#dataStore()} with three precedence rules,
 * the lowest of which is "vendor-specific identifier". Mansart binds that vendor slot to a
 * <b>CDI {@code @Named} lookup</b> on a {@link DataSource} bean — there is no Mansart-proprietary
 * annotation in the API surface; the spec attribute carries the contract.
 *
 * <p>Resolution order, highest priority first:
 * <ol>
 *   <li>Empty value ({@link Repository#DEFAULT_DATA_STORE}) — fall back to the {@link RepositoryRuntime}
 *       {@code @Default} bean produced by {@link MansartRuntimeProducer}.</li>
 *   <li>Value starts with {@code "java:"} — JNDI lookup, must yield a {@link DataSource}. Spec-mandated
 *       behaviour for EE deployments.</li>
 *   <li>Otherwise — CDI lookup of {@code DataSource @Named(value)}.</li>
 * </ol>
 *
 * <p>{@link XADataSource} beans matching the requested name produce a clear error: full XA wiring
 * lands with the JTA bridge in {@code mansart-persistence}; until then, Mansart only consumes plain
 * {@link DataSource}.
 *
 * <p>Each resolved {@link DataSource} is wrapped in a fresh {@link RepositoryRuntime}, then cached by
 * dataStore key — multiple repositories targeting the same store share one runtime.
 */
final class DataStoreResolver {

    private static final ConcurrentMap<String, RepositoryRuntime> CACHE = new ConcurrentHashMap<>();

    private DataStoreResolver() {}

    /**
     * @param lookup  CDI {@link Instance} handle from the synthetic-bean creator
     * @param repoItf the {@code @Repository}-annotated interface being instantiated
     */
    static RepositoryRuntime resolve(Instance<Object> lookup, Class<?> repoItf) {
        Repository ann = repoItf.getAnnotation(Repository.class);
        String key = ann == null ? Repository.DEFAULT_DATA_STORE : ann.dataStore();
        if (key == null || key.isEmpty() || Repository.DEFAULT_DATA_STORE.equals(key)) {
            return lookup.select(RepositoryRuntime.class).get();
        }
        return CACHE.computeIfAbsent(key, k -> buildRuntime(lookup, k));
    }

    private static RepositoryRuntime buildRuntime(Instance<Object> lookup, String key) {
        DataSource ds = key.startsWith("java:") ? jndiLookup(key) : cdiLookup(lookup, key);
        return MansartData.builder().dataSource(ds).build().runtime();
    }

    private static DataSource jndiLookup(String name) {
        try {
            Object obj = new InitialContext().lookup(name);
            if (obj instanceof DataSource ds) return ds;
            throw new MansartDataException(
                    "JNDI lookup of dataStore '" + name + "' yielded " + obj.getClass().getName()
                            + " — expected javax.sql.DataSource (XA bridge lands with mansart-persistence)");
        } catch (NamingException e) {
            throw new MansartDataException("JNDI lookup failed for dataStore '" + name + "'", e);
        }
    }

    private static DataSource cdiLookup(Instance<Object> lookup, String name) {
        Instance<DataSource> ds = lookup.select(DataSource.class, NamedLiteral.of(name));
        if (ds.isResolvable()) return ds.get();
        // Friendlier error path: detect the XA case so the user gets a precise message.
        Instance<XADataSource> xa = lookup.select(XADataSource.class, NamedLiteral.of(name));
        if (xa.isResolvable()) {
            throw new MansartDataException(
                    "@Named(\"" + name + "\") matches an XADataSource — XA wiring lands with the "
                            + "mansart-persistence JTA bridge; for now expose a javax.sql.DataSource bean instead");
        }
        throw new MansartDataException(
                "No DataSource bean found with @Named(\"" + name + "\") for @Repository(dataStore=\""
                        + name + "\")");
    }
}

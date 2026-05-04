package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.Dialect;

import javax.sql.DataSource;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Standalone bootstrap for Mansart Data. CDI bootstrap (Mode B) lives in {@code mansart-data-cdi}.
 *
 * <pre>
 *   MansartData md = MansartData.builder()
 *       .dataSource(ds)
 *       .build();
 *
 *   AuthorRepository authors = md.repository(AuthorRepository.class);
 * </pre>
 */
public final class MansartData {

    private final RepositoryRuntime runtime;
    private final ConcurrentMap<Class<?>, Object> repositories = new ConcurrentHashMap<>();

    private MansartData(RepositoryRuntime runtime) {
        this.runtime = runtime;
    }

    public RepositoryRuntime runtime() { return runtime; }

    public Dialect dialect() { return runtime.dialect(); }

    /**
     * Resolves a {@code @Repository} via the compile-time-generated {@code *Impl} (preferred path).
     * Throws if APT didn't run on the module that declares {@code repositoryInterface} — use
     * {@link #runtimeRepository(Class)} for the M7 runtime fallback in that case.
     */
    @SuppressWarnings("unchecked")
    public <R> R repository(Class<R> repositoryInterface) {
        return (R) repositories.computeIfAbsent(repositoryInterface, this::instantiate);
    }

    /**
     * M7 runtime path — builds a {@link Proxy}-backed implementation that dispatches
     * {@code BasicRepository}/{@code CrudRepository} methods to {@link RepositoryRuntime}, with
     * the {@link io.vidocq.mansart.data.dialect.EntityModel} reconstructed at runtime from the
     * entity's reflection metadata. Use when the compile-time {@code *Impl} doesn't exist (TCK
     * jar, ad-hoc usage). Derived queries / {@code @Query} JDQL / lifecycle annotations land
     * in M7-2 via {@code java.lang.classfile}.
     */
    @SuppressWarnings("unchecked")
    public <R> R runtimeRepository(Class<R> repositoryInterface) {
        return (R) repositories.computeIfAbsent(repositoryInterface,
                itf -> RuntimeRepositoryProxy.create((Class) itf, runtime));
    }

    private <R> R instantiate(Class<R> itf) {
        String implName = itf.getName() + "Impl";
        try {
            Class<?> impl = Class.forName(implName, true, itf.getClassLoader());
            Constructor<?> ctor = impl.getDeclaredConstructor(RepositoryRuntime.class);
            ctor.setAccessible(true);
            @SuppressWarnings("unchecked")
            R instance = (R) ctor.newInstance(runtime);
            return instance;
        } catch (ClassNotFoundException e) {
            throw new MansartDataException("Repository implementation not found: " + implName
                    + " — did mansart-data-processor run on the module declaring " + itf.getName()
                    + "? For environments where APT cannot run on the source (e.g. TCK), use "
                    + "MansartData.runtimeRepository(Class) instead (M7 runtime path).", e);
        } catch (NoSuchMethodException e) {
            throw new MansartDataException("Generated repository " + implName
                    + " has no (RepositoryRuntime) constructor — regenerate sources.", e);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new MansartDataException("Failed to instantiate " + implName, e);
        }
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private DataSource dataSource;
        private Dialect    dialect;

        private Builder() {}

        public Builder dataSource(DataSource ds) { this.dataSource = ds; return this; }

        /** Optional — auto-detected from the data source if omitted. */
        public Builder dialect(Dialect d) { this.dialect = d; return this; }

        public MansartData build() {
            Objects.requireNonNull(dataSource, "dataSource is required");
            Dialect resolved = dialect != null ? dialect : DialectResolver.resolve(dataSource);
            return new MansartData(new RepositoryRuntime(dataSource, resolved));
        }
    }
}

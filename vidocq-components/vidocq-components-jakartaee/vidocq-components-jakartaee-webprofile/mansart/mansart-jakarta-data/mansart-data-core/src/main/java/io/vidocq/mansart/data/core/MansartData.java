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

    @SuppressWarnings("unchecked")
    public <R> R repository(Class<R> repositoryInterface) {
        return (R) repositories.computeIfAbsent(repositoryInterface, this::instantiate);
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
                    + " — did mansart-data-processor run on the module declaring " + itf.getName() + "?", e);
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

package io.vidocq.mansart.data.tck;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;

/** Test-scope CDI producer of an in-memory H2 {@link DataSource}. */
@Singleton
public class H2DataSourceProducer {

    @Produces
    @Singleton
    public DataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mansart-tck-smoke;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return ds;
    }
}

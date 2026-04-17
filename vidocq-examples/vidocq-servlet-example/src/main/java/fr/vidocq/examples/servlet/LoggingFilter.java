package fr.vidocq.examples.servlet;

import jakarta.inject.Singleton;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@Singleton
@WebFilter("/*")
public class LoggingFilter implements Filter {

    private static final System.Logger LOG = System.getLogger(LoggingFilter.class.getName());

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        long start = System.nanoTime();
        HttpServletRequest hreq = (HttpServletRequest) req;
        try {
            chain.doFilter(req, res);
        } finally {
            long elapsedMicros = (System.nanoTime() - start) / 1_000;
            int status = ((HttpServletResponse) res).getStatus();
            LOG.log(System.Logger.Level.INFO,
                    "{0} {1} -> {2} ({3} μs)",
                    hreq.getMethod(), hreq.getRequestURI(), status, elapsedMicros);
        }
    }
}

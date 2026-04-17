package fr.vidocq.vidocq.ext.servlet.chappe.bridge;

import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.DispatchTarget;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * {@link HttpServletRequestWrapper} utilisé pendant un
 * {@link RequestDispatcher#forward forward} : expose les nouveaux servletPath/pathInfo/queryString,
 * et reporte {@link DispatcherType#FORWARD}.
 *
 * <p>Les attributs {@code jakarta.servlet.forward.*} sont positionnés sur la request
 * originale par {@link fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.RequestDispatcherImpl}
 * avant d'invoquer le wrapper.</p>
 */
public final class ForwardedRequest extends HttpServletRequestWrapper {

    private final DispatchTarget target;

    public ForwardedRequest(HttpServletRequest original, DispatchTarget target) {
        super(original);
        this.target = target;
    }

    @Override public String getRequestURI() {
        HttpServletRequest delegate = (HttpServletRequest) getRequest();
        String ctx = delegate.getContextPath();
        return ctx.equals("/") ? target.path() : ctx + target.path();
    }
    @Override public String getServletPath() { return target.servletPath(); }
    @Override public String getPathInfo() { return target.pathInfo(); }
    @Override public String getQueryString() { return target.queryString(); }
    @Override public DispatcherType getDispatcherType() { return DispatcherType.FORWARD; }
}

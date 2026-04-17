package fr.vidocq.vidocq.ext.servlet.chappe.bridge;

import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.DispatchTarget;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Wrapper pour un redispatch initié par {@link jakarta.servlet.AsyncContext#dispatch(String)} :
 * reporte {@link DispatcherType#ASYNC} et les nouveaux {@code servletPath/pathInfo/queryString}.
 */
public final class AsyncDispatchRequest extends HttpServletRequestWrapper {

    private final DispatchTarget target;

    public AsyncDispatchRequest(HttpServletRequest original, DispatchTarget target) {
        super(original);
        this.target = target;
    }

    @Override public DispatcherType getDispatcherType() { return DispatcherType.ASYNC; }
    @Override public String getServletPath() { return target.servletPath(); }
    @Override public String getPathInfo() { return target.pathInfo(); }
    @Override public String getQueryString() { return target.queryString(); }
    @Override public String getRequestURI() {
        HttpServletRequest d = (HttpServletRequest) getRequest();
        String ctx = d.getContextPath();
        return ctx.equals("/") ? target.path() : ctx + target.path();
    }
}

package fr.vidocq.vidocq.ext.servlet.chappe.bridge;

import fr.vidocq.chappe.api.Body;
import fr.vidocq.chappe.api.Handler;
import fr.vidocq.chappe.api.Request;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.StatusCode;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.DispatchResolver;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.DispatchTarget;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.FilterRegistry;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.RequestDispatcherImpl;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.ServletDispatcher;
import fr.vidocq.vidocq.ext.servlet.chappe.async.AsyncContextImpl;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.VidocqFilterChain;
import fr.vidocq.vidocq.ext.servlet.chappe.error.ErrorPageRegistry;
import fr.vidocq.vidocq.ext.servlet.chappe.http.CookieCodec;
import fr.vidocq.vidocq.ext.servlet.chappe.listener.ListenerRegistry;
import fr.vidocq.vidocq.ext.servlet.chappe.session.HttpSessionImpl;
import fr.vidocq.vidocq.ext.servlet.chappe.session.SessionManager;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link Handler} Chappe qui convertit une requête Chappe en cycle servlet :
 * <ol>
 *   <li>Résout le servlet via {@link ServletDispatcher}</li>
 *   <li>Construit la chaîne de filtres applicables via {@link FilterRegistry}</li>
 *   <li>Construit {@link HttpServletRequestImpl} + {@link HttpServletResponseImpl}</li>
 *   <li>Délègue au {@link #invoke(DispatchTarget, HttpServletRequest, HttpServletResponse, DispatcherType) dispatch interne}
 *       réutilisé par {@link RequestDispatcherImpl} pour forward/include</li>
 *   <li>Émet {@code Set-Cookie JSESSIONID} si une session a été créée</li>
 *   <li>Matérialise la réponse Chappe immuable</li>
 * </ol>
 */
public final class ChappeServletBridge implements Handler, RequestDispatcherImpl.Invoker {

    private final ServletDispatcher dispatcher;
    private final FilterRegistry filterRegistry;
    private final fr.vidocq.vidocq.ext.servlet.chappe.container.VidocqServletContext servletContext;
    private final SessionManager sessionManager;
    private final String contextPath;

    public ChappeServletBridge(ServletDispatcher dispatcher,
                               FilterRegistry filterRegistry,
                               fr.vidocq.vidocq.ext.servlet.chappe.container.VidocqServletContext servletContext,
                               SessionManager sessionManager,
                               String contextPath) {
        this.dispatcher = dispatcher;
        this.filterRegistry = filterRegistry;
        this.servletContext = servletContext;
        this.sessionManager = sessionManager;
        this.contextPath = contextPath;
        servletContext.setDispatchInfrastructure(new DispatchResolver(dispatcher), this);
    }

    /** Construction sans sessions. */
    public ChappeServletBridge(ServletDispatcher dispatcher,
                               FilterRegistry filterRegistry,
                               fr.vidocq.vidocq.ext.servlet.chappe.container.VidocqServletContext servletContext,
                               String contextPath) {
        this(dispatcher, filterRegistry, servletContext, null, contextPath);
    }

    /** Construction minimale (compat tests). */
    public ChappeServletBridge(ServletDispatcher dispatcher,
                               fr.vidocq.vidocq.ext.servlet.chappe.container.VidocqServletContext servletContext,
                               String contextPath) {
        this(dispatcher, new FilterRegistry(List.of()), servletContext, null, contextPath);
    }

    @Override
    public Response handle(Request request) throws Exception {
        String path = request.path();
        Optional<ServletDispatcher.Mapping> match = dispatcher.find(path);
        ListenerRegistry registry = servletContext.listenerRegistry();

        HttpServletRequestImpl req;
        HttpServletResponseImpl res = new HttpServletResponseImpl();

        if (match.isEmpty()) {
            req = new HttpServletRequestImpl(request, servletContext, contextPath, path, null,
                    sessionManager);
            List<Filter> filters = filterRegistry.chainFor(path, DispatcherType.REQUEST);
            if (filters.isEmpty()) return notFound();

            registry.fireRequestInitialized(servletContext, req);
            try {
                new VidocqFilterChain(filters, null).doFilter(req, res);
            } catch (ServletException e) {
                registry.fireRequestDestroyed(servletContext, req);
                return error(e);
            }
            registry.fireRequestDestroyed(servletContext, req);
            if (res.getStatus() == 200 && res.bodyBytes().length == 0) return notFound();
            maybeAttachSessionCookie(req, res);
            return toChappeResponse(res);
        }

        ServletDispatcher.Mapping m = match.get();
        String servletPath = DispatchResolver.servletPathFor(m, path);
        String pathInfo = DispatchResolver.pathInfoFor(m, path, servletPath);
        DispatchTarget target = new DispatchTarget(m.servlet(), m.servletName(), path, servletPath,
                pathInfo, request.query());
        req = new HttpServletRequestImpl(request, servletContext, contextPath, servletPath, pathInfo,
                sessionManager);
        req.bindResponse(res);

        registry.fireRequestInitialized(servletContext, req);
        Throwable thrown = null;
        try {
            var enforcer = new fr.vidocq.vidocq.ext.servlet.chappe.security.SecurityConstraintEnforcer(
                    servletContext.securityProvider());
            if (!enforcer.enforce(m.servlet().getClass(), req, res)) {
                registry.fireRequestDestroyed(servletContext, req);
                return toChappeResponse(res);
            }
            invoke(target, req, res, DispatcherType.REQUEST);
            thrown = awaitAsyncIfStarted(req, res);
        } catch (ServletException | IOException | RuntimeException e) {
            thrown = e;
        }
        registry.fireRequestDestroyed(servletContext, req);

        try {
            maybeHandleError(req, res, thrown, target.servletName());
        } catch (ServletException e) {
            return error(e);
        }
        if (thrown != null && !errorPageHandled(req)) {
            return error(thrown);
        }
        maybeAttachSessionCookie(req, res);
        return toChappeResponse(res);
    }

    private boolean errorPageHandled(HttpServletRequestImpl req) {
        return req.getAttribute("jakarta.servlet.error.handled") != null;
    }

    /**
     * Si le servlet a démarré un async, bloque jusqu'à complete/dispatch/timeout. En cas de
     * dispatch, re-résout et ré-exécute la chaîne sous {@link DispatcherType#ASYNC}.
     * Renvoie une {@link Throwable} si un timeout s'est produit et n'a pas été géré par listener.
     */
    private Throwable awaitAsyncIfStarted(HttpServletRequestImpl req, HttpServletResponseImpl res) {
        AsyncContextImpl ac = req.asyncContextInternal();
        if (ac == null) return null;
        ac.awaitCompletion();
        if (ac.hasDispatch()) {
            String dispatchPath = ac.dispatchPath();
            String relative = dispatchPath.startsWith(contextPath) && !contextPath.equals("/")
                    ? dispatchPath.substring(contextPath.length()) : dispatchPath;
            var target = new DispatchResolver(dispatcher).resolve(relative).orElse(null);
            if (target != null) {
                try {
                    var wrapped = new AsyncDispatchRequest(req, target);
                    invoke(target, wrapped, res, DispatcherType.ASYNC);
                } catch (ServletException | IOException | RuntimeException e) {
                    return e;
                }
            }
        }
        if (ac.timedOut() && !res.isCommitted() && res.bodyBytes().length == 0) {
            try { res.sendError(503, "async timeout"); }
            catch (IOException ignored) {}
        }
        return null;
    }

    private void maybeHandleError(HttpServletRequestImpl req, HttpServletResponseImpl res,
                                  Throwable thrown, String servletName) throws ServletException {
        ErrorPageRegistry pages = servletContext.errorPages();
        String location = null;
        Integer errorStatus = null;
        if (thrown != null) {
            location = pages.findByException(thrown).orElse(null);
            // Servlet 6.1 §2.3.3.2 : UnavailableException remonte explicitement
            // un status 404 (permanent) ou 503 (temporary) au lieu du 500 générique.
            Throwable root = thrown;
            while (root.getCause() != null && !(root instanceof jakarta.servlet.UnavailableException)) {
                root = root.getCause();
            }
            if (root instanceof jakarta.servlet.UnavailableException ue) {
                errorStatus = ue.isPermanent() ? 404 : 503;
            } else {
                errorStatus = 500;
            }
        } else if (res.isErrorTriggered()) {
            errorStatus = res.getStatus();
            location = pages.findByStatus(errorStatus).orElse(null);
        }
        if (location == null) {
            // Pas d'error-page mappée : on applique tout de même le status approprié
            // (404/503 pour UnavailableException) et on court-circuite le error()
            // générique du handler.
            if (thrown instanceof jakarta.servlet.UnavailableException
                    || (thrown != null && thrown.getCause() instanceof jakarta.servlet.UnavailableException)) {
                res.clearErrorState();
                res.resetBuffer();
                try { res.sendError(errorStatus, thrown.getMessage()); }
                catch (IOException ignored) {}
                req.setAttribute("jakarta.servlet.error.handled", Boolean.TRUE);
            }
            return;
        }

        var target = new DispatchResolver(dispatcher).resolve(location).orElse(null);
        if (target == null) return;

        res.clearErrorState();
        res.resetBuffer();

        req.setAttribute("jakarta.servlet.error.status_code", errorStatus);
        req.setAttribute("jakarta.servlet.error.request_uri", req.getRequestURI());
        req.setAttribute("jakarta.servlet.error.servlet_name", servletName);
        if (thrown != null) {
            req.setAttribute("jakarta.servlet.error.exception", thrown);
            req.setAttribute("jakarta.servlet.error.exception_type", thrown.getClass());
            req.setAttribute("jakarta.servlet.error.message", thrown.getMessage());
        } else if (res.errorMessage() != null) {
            req.setAttribute("jakarta.servlet.error.message", res.errorMessage());
        }
        req.setAttribute("jakarta.servlet.error.handled", Boolean.TRUE);

        try {
            res.setStatus(errorStatus);
            invoke(target, req, res, DispatcherType.ERROR);
        } catch (IOException | RuntimeException e) {
            throw new ServletException("error dispatch failed", e);
        }
    }

    /**
     * Dispatch interne — calcule la filter chain pour le {@link DispatcherType} donné
     * puis invoque le servlet cible. Réutilisé par {@link RequestDispatcherImpl} pour
     * forward/include et par {@link #handle(Request)} pour REQUEST.
     */
    @Override
    public void invoke(DispatchTarget target, HttpServletRequest req, HttpServletResponse res,
                       DispatcherType type) throws IOException, ServletException {
        List<Filter> filters = filterRegistry.chainFor(target.path(), type);
        new VidocqFilterChain(filters, target.servlet()).doFilter(req, res);
    }

    private void maybeAttachSessionCookie(HttpServletRequestImpl req, HttpServletResponseImpl res) {
        HttpSessionImpl session = req.boundSession();
        if (session == null || session.isInvalidated()) return;
        String requested = req.getRequestedSessionId();
        if (!session.getId().equals(requested)) {
            Cookie c = new Cookie(SessionManager.COOKIE_NAME, session.getId());
            c.setPath("/".equals(contextPath) ? "/" : contextPath);
            c.setHttpOnly(true);
            res.addHeader("Set-Cookie", CookieCodec.serializeSetCookie(c));
        }
    }

    static Response toChappeResponse(HttpServletResponseImpl res) {
        var builder = Response.builder()
                .status(StatusCode.of(res.getStatus()))
                .body(Body.of(res.bodyBytes()));
        for (Map.Entry<String, List<String>> e : res.allHeaders().entrySet()) {
            for (String v : e.getValue()) {
                builder.header(e.getKey(), v);
            }
        }
        return builder.build();
    }

    private static Response notFound() {
        return Response.builder()
                .status(StatusCode.NOT_FOUND)
                .header("Content-Type", "text/plain")
                .body(Body.of("Not Found".getBytes()))
                .build();
    }

    private static Response error(Throwable e) {
        // Log full stack for debugging — silent if DEBUG not set.
        if (Boolean.getBoolean("vidocq.servlet.debug")) {
            e.printStackTrace(System.err);
        }
        return Response.builder()
                .status(StatusCode.INTERNAL_SERVER_ERROR)
                .header("Content-Type", "text/plain")
                .body(Body.of(("Servlet error: " + e.getMessage()).getBytes()))
                .build();
    }
}

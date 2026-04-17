package fr.vidocq.vidocq.ext.servlet.chappe.bridge;

import fr.vidocq.chappe.api.Body;
import fr.vidocq.chappe.api.Handler;
import fr.vidocq.chappe.api.Request;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.StatusCode;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.FilterRegistry;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.ServletDispatcher;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.VidocqFilterChain;
import fr.vidocq.vidocq.ext.servlet.chappe.http.CookieCodec;
import fr.vidocq.vidocq.ext.servlet.chappe.listener.ListenerRegistry;
import fr.vidocq.vidocq.ext.servlet.chappe.session.HttpSessionImpl;
import fr.vidocq.vidocq.ext.servlet.chappe.session.SessionManager;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link Handler} Chappe qui convertit une requête Chappe en cycle servlet :
 * <ol>
 *   <li>Résout le servlet via {@link ServletDispatcher}</li>
 *   <li>Construit la chaîne de filtres applicables via {@link FilterRegistry}</li>
 *   <li>Construit {@link HttpServletRequestImpl} + {@link HttpServletResponseImpl}</li>
 *   <li>Appelle {@code chain.doFilter(req, res)} qui invoquera finalement le servlet</li>
 *   <li>Émet {@code Set-Cookie JSESSIONID} si une session a été créée</li>
 *   <li>Matérialise la réponse Chappe immuable</li>
 * </ol>
 */
public final class ChappeServletBridge implements Handler {

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
            if (filters.isEmpty()) {
                return notFound();
            }
            registry.fireRequestInitialized(servletContext, req);
            VidocqFilterChain chain = new VidocqFilterChain(filters, null);
            try {
                chain.doFilter(req, res);
            } catch (ServletException e) {
                registry.fireRequestDestroyed(servletContext, req);
                return error(e);
            }
            registry.fireRequestDestroyed(servletContext, req);
            if (res.getStatus() == 200 && res.bodyBytes().length == 0) {
                return notFound();
            }
            maybeAttachSessionCookie(req, res);
            return toChappeResponse(res);
        }

        ServletDispatcher.Mapping m = match.get();
        String servletPath = servletPathFor(m, path);
        String pathInfo = pathInfoFor(m, path, servletPath);
        req = new HttpServletRequestImpl(request, servletContext, contextPath, servletPath, pathInfo,
                sessionManager);

        List<Filter> filters = filterRegistry.chainFor(path, DispatcherType.REQUEST);
        VidocqFilterChain chain = new VidocqFilterChain(filters, m.servlet());

        registry.fireRequestInitialized(servletContext, req);
        try {
            chain.doFilter(req, res);
        } catch (ServletException e) {
            registry.fireRequestDestroyed(servletContext, req);
            return error(e);
        }
        registry.fireRequestDestroyed(servletContext, req);
        maybeAttachSessionCookie(req, res);
        return toChappeResponse(res);
    }

    private void maybeAttachSessionCookie(HttpServletRequestImpl req, HttpServletResponseImpl res) {
        HttpSessionImpl session = req.boundSession();
        if (session == null || session.isInvalidated()) return;
        // Émet un Set-Cookie uniquement pour les sessions nouvellement créées
        // et non déjà présentées par le client.
        String requested = req.getRequestedSessionId();
        if (!session.getId().equals(requested)) {
            Cookie c = new Cookie(SessionManager.COOKIE_NAME, session.getId());
            c.setPath("/".equals(contextPath) ? "/" : contextPath);
            c.setHttpOnly(true);
            res.addHeader("Set-Cookie", CookieCodec.serializeSetCookie(c));
        }
    }

    static String servletPathFor(ServletDispatcher.Mapping m, String path) {
        return switch (m.matcher().kind()) {
            case EXACT -> m.matcher().pattern();
            case PREFIX -> {
                String prefix = m.matcher().pattern();
                yield prefix.substring(0, prefix.length() - 2); // drop /*
            }
            case EXTENSION -> path;
            case DEFAULT, EMPTY -> "";
        };
    }

    static String pathInfoFor(ServletDispatcher.Mapping m, String path, String servletPath) {
        return switch (m.matcher().kind()) {
            case PREFIX -> {
                String rest = path.substring(servletPath.length());
                yield rest.isEmpty() ? null : rest;
            }
            case EXACT, EXTENSION, DEFAULT, EMPTY -> null;
        };
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

    private static Response error(ServletException e) {
        return Response.builder()
                .status(StatusCode.INTERNAL_SERVER_ERROR)
                .header("Content-Type", "text/plain")
                .body(Body.of(("Servlet error: " + e.getMessage()).getBytes()))
                .build();
    }
}

package fr.vidocq.vidocq.ext.servlet.chappe.bridge;

import fr.vidocq.chappe.api.Body;
import fr.vidocq.chappe.api.Handler;
import fr.vidocq.chappe.api.Request;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.StatusCode;
import fr.vidocq.vidocq.ext.servlet.chappe.container.VidocqServletContext;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.ServletDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link Handler} Chappe qui convertit une requête Chappe en cycle servlet :
 * <ol>
 *   <li>Résout le servlet via {@link ServletDispatcher}</li>
 *   <li>Construit {@link HttpServletRequestImpl} + {@link HttpServletResponseImpl}</li>
 *   <li>Appelle {@link Servlet#service(jakarta.servlet.ServletRequest, jakarta.servlet.ServletResponse)}</li>
 *   <li>Matérialise la réponse Chappe immuable</li>
 * </ol>
 */
public final class ChappeServletBridge implements Handler {

    private final ServletDispatcher dispatcher;
    private final VidocqServletContext servletContext;
    private final String contextPath;

    public ChappeServletBridge(ServletDispatcher dispatcher, VidocqServletContext servletContext,
                               String contextPath) {
        this.dispatcher = dispatcher;
        this.servletContext = servletContext;
        this.contextPath = contextPath;
    }

    @Override
    public Response handle(Request request) throws Exception {
        String path = request.path();
        Optional<ServletDispatcher.Mapping> match = dispatcher.find(path);
        if (match.isEmpty()) {
            return Response.builder()
                    .status(StatusCode.NOT_FOUND)
                    .body(Body.of("Not Found".getBytes()))
                    .header("Content-Type", "text/plain")
                    .build();
        }

        ServletDispatcher.Mapping m = match.get();
        String servletPath = servletPathFor(m, path);
        String pathInfo = pathInfoFor(m, path, servletPath);

        HttpServletRequestImpl req = new HttpServletRequestImpl(
                request, servletContext, contextPath, servletPath, pathInfo);
        HttpServletResponseImpl res = new HttpServletResponseImpl();

        try {
            m.servlet().service(req, res);
        } catch (ServletException e) {
            return Response.builder()
                    .status(StatusCode.INTERNAL_SERVER_ERROR)
                    .body(Body.of(("Servlet error: " + e.getMessage()).getBytes()))
                    .build();
        }

        return toChappeResponse(res);
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
}

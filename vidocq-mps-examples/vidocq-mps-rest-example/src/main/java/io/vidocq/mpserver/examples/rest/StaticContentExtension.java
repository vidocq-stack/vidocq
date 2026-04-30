package io.vidocq.mpserver.examples.rest;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.StaticFileHandler;
import io.vidocq.mpserver.ext.chappe.ChappeListener;
import io.vidocq.mpserver.ext.chappe.ChappeMountPoint;
import io.vidocq.mpserver.spi.ExtensionContext;
import io.vidocq.mpserver.spi.VidocqExtension;

/**
 * Extension Vidocq qui sert l'UI statique de la todo-list depuis le classpath
 * (répertoire {@code static/}).
 *
 * <p>Priorité 600 — postérieure à {@code CassiniExtension} (500), pour que
 * Cassini contribue d'abord ses routes {@code /api/*} avant que ce handler
 * ne mount le static sur la racine.</p>
 */
public final class StaticContentExtension implements VidocqExtension {

    @Override
    public String name() {
        return "static-content";
    }

    @Override
    public int priority() {
        return 600;
    }

    @Override
    public void onStart(ExtensionContext context) {
        Handler base = StaticFileHandler.builder()
                .addClasspath("static")
                .indexFile("index.html")
                .cacheInMemory(true)
                .build();

        // StaticFileHandler ne sait pas résoudre un répertoire classpath en
        // index.html quand pathInfo se termine par "/" (les directories de jar
        // n'exposent pas leur contenu via URLConnection). On rewrite la requête
        // pour pointer explicitement sur l'index dans ces cas-là.
        Handler indexed = (Request req) -> {
            String info = req.pathInfo();
            if (info == null || info.isEmpty() || info.endsWith("/")) {
                String resolved = (info == null || info.isEmpty()) ? "/index.html" : info + "index.html";
                return base.handle(rewritePathInfo(req, resolved));
            }
            return base.handle(req);
        };

        ChappeMountPoint.instance().mount(ChappeListener.DEFAULT, "", indexed);
    }

    private static Request rewritePathInfo(Request original, String newPathInfo) {
        return new Request() {
            @Override public io.vidocq.chappe.api.HttpMethod method() { return original.method(); }
            @Override public java.net.URI uri() { return original.uri(); }
            @Override public String path() { return original.path(); }
            @Override public String query() { return original.query(); }
            @Override public io.vidocq.chappe.api.HttpVersion version() { return original.version(); }
            @Override public io.vidocq.chappe.api.Headers headers() { return original.headers(); }
            @Override public io.vidocq.chappe.api.Body body() { return original.body(); }
            @Override public java.util.Map<String, String> pathParams() { return original.pathParams(); }
            @Override public java.util.Map<String, String> queryParams() { return original.queryParams(); }
            @Override public String contextPath() { return original.contextPath(); }
            @Override public String pathInfo() { return newPathInfo; }
            @Override public Object attribute(String key) { return original.attribute(key); }
            @Override public Request attribute(String key, Object value) { original.attribute(key, value); return this; }
            @Override public java.net.InetSocketAddress remoteAddress() { return original.remoteAddress(); }
            @Override public java.net.InetSocketAddress localAddress() { return original.localAddress(); }
            @Override public boolean isSecure() { return original.isSecure(); }
            @Override public String scheme() { return original.scheme(); }
        };
    }
}

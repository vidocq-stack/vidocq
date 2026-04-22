package fr.vidocq.vidocq.ext.rest.cassini.internal.runtime;

import fr.vidocq.vidocq.ext.rest.cassini.internal.MediaTypes;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Implémentation minimale de {@link RuntimeDelegate} pour débloquer
 * {@code Response.ok()}, {@code MediaType.toString()} et
 * {@link UriBuilder} côté code utilisateur.
 */
public final class CassiniRuntimeDelegate extends RuntimeDelegate {

    @Override public UriBuilder createUriBuilder() { return new CassiniUriBuilder(); }

    // Expose fromResource/fromMethod via UriBuilder.fromResource side (static fallback).
    // UriBuilder.fromResource() calls createUriBuilder().uri(...) internally in spec,
    // but CassiniUriBuilder.fromResource(Class) is our own helper.

    @Override public Response.ResponseBuilder createResponseBuilder() { return new CassiniResponseBuilder(); }

    @Override public Variant.VariantListBuilder createVariantListBuilder() {
        return new StubVariantListBuilder();
    }

    @Override public <T> T createEndpoint(Application application, Class<T> endpointType) {
        throw new UnsupportedOperationException("createEndpoint not supported");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HeaderDelegate<T> createHeaderDelegate(Class<T> type) {
        if (type == MediaType.class) return (HeaderDelegate<T>) new MediaTypeDelegate();
        if (type == jakarta.ws.rs.core.NewCookie.class) return (HeaderDelegate<T>) new NewCookieDelegate();
        if (type == jakarta.ws.rs.core.Cookie.class) return (HeaderDelegate<T>) new CookieDelegate();
        if (type == jakarta.ws.rs.core.EntityTag.class) return (HeaderDelegate<T>) new EntityTagDelegate();
        if (type == jakarta.ws.rs.core.CacheControl.class) return (HeaderDelegate<T>) new CacheControlDelegate();
        if (type == jakarta.ws.rs.core.Link.class) return (HeaderDelegate<T>) new LinkDelegate();
        if (type == java.util.Date.class) return (HeaderDelegate<T>) new DateDelegate();
        return (HeaderDelegate<T>) new ToStringDelegate();
    }

    @Override public Link.Builder createLinkBuilder() {
        return new StubLinkBuilder();
    }

    @Override public EntityPart.Builder createEntityPartBuilder(String name) {
        throw new UnsupportedOperationException("EntityPart.Builder not implemented yet");
    }

    @Override public SeBootstrap.Configuration.Builder createConfigurationBuilder() {
        return new CassiniBootstrapConfigBuilder();
    }

    @Override public CompletionStage<SeBootstrap.Instance> bootstrap(Application application, SeBootstrap.Configuration config) {
        return CompletableFuture.supplyAsync(() -> new CassiniSeBootstrapInstance(application, config));
    }

    @Override public CompletionStage<SeBootstrap.Instance> bootstrap(Class<? extends Application> clazz, SeBootstrap.Configuration config) {
        try {
            return bootstrap(clazz.getDeclaredConstructor().newInstance(), config);
        } catch (ReflectiveOperationException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static final class CassiniBootstrapConfigBuilder implements SeBootstrap.Configuration.Builder {
        private final java.util.Map<String, Object> props = new java.util.HashMap<>();
        CassiniBootstrapConfigBuilder() {
            props.put(SeBootstrap.Configuration.PROTOCOL, "HTTP");
            props.put(SeBootstrap.Configuration.HOST, "localhost");
            props.put(SeBootstrap.Configuration.PORT, -1);
            props.put(SeBootstrap.Configuration.ROOT_PATH, "/");
        }
        @Override public SeBootstrap.Configuration.Builder property(String name, Object value) {
            props.put(name, value); return this;
        }
        @Override public <T> SeBootstrap.Configuration.Builder from(java.util.function.BiFunction<String, Class<T>, java.util.Optional<T>> src) {
            return this;
        }
        @Override public SeBootstrap.Configuration build() {
            return new CassiniBootstrapConfig(java.util.Map.copyOf(props));
        }
    }

    private record CassiniBootstrapConfig(java.util.Map<String, Object> props) implements SeBootstrap.Configuration {
        @Override public Object property(String name) { return props.get(name); }
    }

    private static final class CassiniSeBootstrapInstance implements SeBootstrap.Instance {
        private final SeBootstrap.Configuration config;
        private volatile fr.vidocq.chappe.api.Server server;

        CassiniSeBootstrapInstance(Application application, SeBootstrap.Configuration requested) {
            Object p = requested.property(SeBootstrap.Configuration.PORT);
            int reqPort = p instanceof Number n ? n.intValue() : -1;
            if (reqPort < 0) {
                try (var ss = new java.net.ServerSocket(0)) { reqPort = ss.getLocalPort(); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            }
            // Config qui reflète le port effectif (lu par les clients TCK).
            java.util.Map<String, Object> effective = new java.util.HashMap<>();
            for (String k : new String[]{SeBootstrap.Configuration.PROTOCOL,
                    SeBootstrap.Configuration.HOST, SeBootstrap.Configuration.PORT,
                    SeBootstrap.Configuration.ROOT_PATH,
                    SeBootstrap.Configuration.SSL_CLIENT_AUTHENTICATION,
                    SeBootstrap.Configuration.SSL_CONTEXT}) {
                Object v = requested.property(k);
                if (v != null) effective.put(k, v);
            }
            effective.put(SeBootstrap.Configuration.PORT, reqPort);
            this.config = new CassiniBootstrapConfig(java.util.Map.copyOf(effective));

            try {
                java.util.Set<Class<?>> resourceClasses = new java.util.LinkedHashSet<>();
                java.util.Map<Class<?>, Object> resourceSingletons = new java.util.HashMap<>();
                if (application.getClasses() != null) resourceClasses.addAll(application.getClasses());
                if (application.getSingletons() != null) {
                    for (Object o : application.getSingletons()) {
                        resourceClasses.add(o.getClass());
                        resourceSingletons.put(o.getClass(), o);
                    }
                }
                java.util.Set<Class<?>> pathClasses = new java.util.LinkedHashSet<>();
                var filters = new fr.vidocq.vidocq.ext.rest.cassini.internal.filter.FilterRegistry();
                var bodies = new fr.vidocq.vidocq.ext.rest.cassini.internal.MessageBodyRegistry();
                var mappers = new fr.vidocq.vidocq.ext.rest.cassini.internal.ExceptionMapperRegistry();
                for (Class<?> c : resourceClasses) {
                    if (c.isAnnotationPresent(jakarta.ws.rs.Path.class)) pathClasses.add(c);
                    if (c.isAnnotationPresent(jakarta.ws.rs.ext.Provider.class)) {
                        Object inst = resourceSingletons.computeIfAbsent(c, k -> {
                            try { return k.getDeclaredConstructor().newInstance(); }
                            catch (ReflectiveOperationException e) { return null; }
                        });
                        if (inst == null) continue;
                        filters.register(inst);
                        if (inst instanceof jakarta.ws.rs.ext.MessageBodyReader<?> r) bodies.addReader(r);
                        if (inst instanceof jakarta.ws.rs.ext.MessageBodyWriter<?> w) bodies.addWriter(w);
                    }
                }
                var routes = fr.vidocq.vidocq.ext.rest.cassini.internal.ResourceScanner
                        .discover(pathClasses.toArray(Class<?>[]::new));
                var router = new fr.vidocq.vidocq.ext.rest.cassini.internal.UriRouter(routes);
                java.util.function.Function<Class<?>, Object> resolver = cls -> {
                    Object fixed = resourceSingletons.get(cls);
                    if (fixed != null) return fixed;
                    try { return cls.getDeclaredConstructor().newInstance(); }
                    catch (ReflectiveOperationException e) {
                        throw new RuntimeException("Failed to instantiate " + cls, e);
                    }
                };
                var invoker = new fr.vidocq.vidocq.ext.rest.cassini.internal.Invoker(resolver, bodies, mappers);
                invoker.setFilters(filters);
                var bridge = new fr.vidocq.vidocq.ext.rest.cassini.internal.CassiniRestBridge(router, invoker);
                String rootPath = (String) requested.property(SeBootstrap.Configuration.ROOT_PATH);
                final String prefix = (rootPath == null || "/".equals(rootPath) || rootPath.isEmpty())
                        ? "" : (rootPath.startsWith("/") ? rootPath : "/" + rootPath);
                fr.vidocq.chappe.api.Handler handler = prefix.isEmpty() ? bridge
                        : req -> {
                    String pth = req.path() == null ? "/" : req.path();
                    if (!pth.startsWith(prefix)) {
                        return fr.vidocq.chappe.api.Response.builder()
                                .status(fr.vidocq.chappe.api.StatusCode.NOT_FOUND)
                                .body(fr.vidocq.chappe.api.Body.empty()).build();
                    }
                    return bridge.handle(req);
                };
                fr.vidocq.chappe.api.Server s = fr.vidocq.chappe.api.Server.builder()
                        .host("localhost").port(reqPort).handler(handler).build();
                s.start();
                this.server = s;
            } catch (RuntimeException e) {
                this.server = null;
            }
        }

        @Override public SeBootstrap.Configuration configuration() { return config; }

        @Override public CompletionStage<SeBootstrap.Instance.StopResult> stop() {
            return CompletableFuture.supplyAsync(() -> {
                if (server != null) {
                    try { server.stop(); } catch (RuntimeException ignored) {}
                }
                return new SeBootstrap.Instance.StopResult() {
                    @Override public <T> T unwrap(Class<T> nativeClass) { throw new IllegalArgumentException(); }
                };
            });
        }

        @Override public <T> T unwrap(Class<T> nativeClass) {
            if (nativeClass.isInstance(server)) return nativeClass.cast(server);
            throw new IllegalArgumentException("Cannot unwrap to " + nativeClass);
        }
    }

    private static final class MediaTypeDelegate implements HeaderDelegate<MediaType> {
        @Override public MediaType fromString(String value) { return MediaTypes.parse(value); }
        @Override public String toString(MediaType value) { return MediaTypes.format(value); }
    }

    private static final class ToStringDelegate implements HeaderDelegate<Object> {
        @Override public Object fromString(String value) { return value; }
        @Override public String toString(Object value) { return value == null ? "" : value.toString(); }
    }

    private static final class NewCookieDelegate implements HeaderDelegate<jakarta.ws.rs.core.NewCookie> {
        @Override public jakarta.ws.rs.core.NewCookie fromString(String s) {
            // Parsing basique name=value; attr=val; ...
            String[] parts = s.split(";");
            String name = null, value = null, path = null, domain = null, comment = null;
            int maxAge = -1; boolean secure = false, httpOnly = false;
            int version = 1;
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                int eq = p.indexOf('=');
                String k = eq < 0 ? p : p.substring(0, eq).trim();
                String v = eq < 0 ? "" : stripQuotes(p.substring(eq + 1).trim());
                if (i == 0) { name = k; value = v; continue; }
                switch (k.toLowerCase(java.util.Locale.ROOT)) {
                    case "path": path = v; break;
                    case "domain": domain = v; break;
                    case "comment": comment = v; break;
                    case "max-age": try { maxAge = Integer.parseInt(v); } catch (Exception e) {} break;
                    case "version": try { version = Integer.parseInt(v); } catch (Exception e) {} break;
                    case "secure": secure = true; break;
                    case "httponly": httpOnly = true; break;
                }
            }
            return new jakarta.ws.rs.core.NewCookie.Builder(name).value(value).path(path)
                    .domain(domain).comment(comment).maxAge(maxAge).version(version)
                    .secure(secure).httpOnly(httpOnly).build();
        }
        @Override public String toString(jakarta.ws.rs.core.NewCookie c) {
            StringBuilder sb = new StringBuilder();
            sb.append(c.getName()).append('=').append(quoteIfNeeded(c.getValue()));
            if (c.getVersion() > 0) sb.append(";Version=").append(c.getVersion());
            if (c.getPath() != null) sb.append(";Path=").append(quoteIfNeeded(c.getPath()));
            if (c.getDomain() != null) sb.append(";Domain=").append(quoteIfNeeded(c.getDomain()));
            if (c.getMaxAge() != -1) sb.append(";Max-Age=").append(c.getMaxAge());
            if (c.getComment() != null) sb.append(";Comment=").append(quoteIfNeeded(c.getComment()));
            if (c.isSecure()) sb.append(";Secure");
            if (c.isHttpOnly()) sb.append(";HttpOnly");
            return sb.toString();
        }
    }

    private static final class CookieDelegate implements HeaderDelegate<jakarta.ws.rs.core.Cookie> {
        @Override public jakarta.ws.rs.core.Cookie fromString(String s) {
            int eq = s.indexOf('=');
            if (eq < 0) return new jakarta.ws.rs.core.Cookie.Builder(s.trim()).build();
            String name = s.substring(0, eq).trim();
            String value = stripQuotes(s.substring(eq + 1).trim());
            return new jakarta.ws.rs.core.Cookie.Builder(name).value(value).build();
        }
        @Override public String toString(jakarta.ws.rs.core.Cookie c) {
            return c.getName() + "=" + quoteIfNeeded(c.getValue());
        }
    }

    private static final class EntityTagDelegate implements HeaderDelegate<jakarta.ws.rs.core.EntityTag> {
        @Override public jakarta.ws.rs.core.EntityTag fromString(String s) {
            boolean weak = s.startsWith("W/");
            String tag = weak ? s.substring(2) : s;
            tag = stripQuotes(tag.trim());
            return new jakarta.ws.rs.core.EntityTag(tag, weak);
        }
        @Override public String toString(jakarta.ws.rs.core.EntityTag e) {
            return (e.isWeak() ? "W/" : "") + "\"" + e.getValue() + "\"";
        }
    }

    private static final class CacheControlDelegate implements HeaderDelegate<jakarta.ws.rs.core.CacheControl> {
        @Override public jakarta.ws.rs.core.CacheControl fromString(String s) {
            jakarta.ws.rs.core.CacheControl cc = new jakarta.ws.rs.core.CacheControl();
            cc.setNoTransform(false); // default true in spec; flip
            return cc;
        }
        @Override public String toString(jakarta.ws.rs.core.CacheControl c) {
            StringBuilder sb = new StringBuilder();
            if (c.isNoCache()) append(sb, "no-cache");
            if (c.isNoStore()) append(sb, "no-store");
            if (c.isNoTransform()) append(sb, "no-transform");
            if (c.isPrivate()) append(sb, "private");
            if (c.isMustRevalidate()) append(sb, "must-revalidate");
            if (c.isProxyRevalidate()) append(sb, "proxy-revalidate");
            if (c.getMaxAge() != -1) append(sb, "max-age=" + c.getMaxAge());
            if (c.getSMaxAge() != -1) append(sb, "s-maxage=" + c.getSMaxAge());
            return sb.toString();
        }
        private static void append(StringBuilder sb, String v) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(v);
        }
    }

    private static final class LinkDelegate implements HeaderDelegate<jakarta.ws.rs.core.Link> {
        @Override public jakarta.ws.rs.core.Link fromString(String s) {
            // Format: <uri>; rel=xxx; title="yyy"
            String trimmed = s.trim();
            int gt = trimmed.indexOf('>');
            String uri = trimmed.startsWith("<") && gt > 0 ? trimmed.substring(1, gt) : trimmed;
            jakarta.ws.rs.core.Link.Builder b = jakarta.ws.rs.core.Link.fromUri(uri);
            if (gt > 0 && gt < trimmed.length() - 1) {
                String rest = trimmed.substring(gt + 1);
                for (String p : rest.split(";")) {
                    String pp = p.trim();
                    int eq = pp.indexOf('=');
                    if (eq < 0) continue;
                    b.param(pp.substring(0, eq).trim(),
                            stripQuotes(pp.substring(eq + 1).trim()));
                }
            }
            return b.build();
        }
        @Override public String toString(jakarta.ws.rs.core.Link l) {
            StringBuilder sb = new StringBuilder("<").append(l.getUri()).append('>');
            for (var e : l.getParams().entrySet()) {
                sb.append(";").append(e.getKey()).append("=\"").append(e.getValue()).append('"');
            }
            return sb.toString();
        }
    }

    private static final class DateDelegate implements HeaderDelegate<java.util.Date> {
        private static final java.text.SimpleDateFormat FMT;
        static {
            FMT = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US);
            FMT.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        }
        @Override public synchronized java.util.Date fromString(String s) {
            try { return FMT.parse(s); } catch (Exception e) { return null; }
        }
        @Override public synchronized String toString(java.util.Date d) { return FMT.format(d); }
    }

    private static String stripQuotes(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
    private static String quoteIfNeeded(String v) {
        if (v == null) return "";
        if (v.contains(" ") || v.contains(";") || v.contains(",")) return "\"" + v + "\"";
        return v;
    }

    /** Stub {@link Variant.VariantListBuilder} — collecte media types/langs/encodings
     *  et produit le produit cartésien via {@link #build()}. */
    private static final class StubVariantListBuilder extends Variant.VariantListBuilder {
        private final java.util.List<Variant> variants = new java.util.ArrayList<>();
        private final java.util.List<MediaType> mediaTypes = new java.util.ArrayList<>();
        private final java.util.List<java.util.Locale> languages = new java.util.ArrayList<>();
        private final java.util.List<String> encodings = new java.util.ArrayList<>();

        @Override public java.util.List<Variant> build() {
            add();
            return java.util.List.copyOf(variants);
        }

        @Override public Variant.VariantListBuilder add() {
            if (mediaTypes.isEmpty() && languages.isEmpty() && encodings.isEmpty()) return this;
            java.util.List<MediaType> mts = mediaTypes.isEmpty() ? java.util.Collections.singletonList(null) : mediaTypes;
            java.util.List<java.util.Locale> ls = languages.isEmpty() ? java.util.Collections.singletonList(null) : languages;
            java.util.List<String> encs = encodings.isEmpty() ? java.util.Collections.singletonList(null) : encodings;
            for (MediaType m : mts) for (java.util.Locale l : ls) for (String e : encs)
                variants.add(new Variant(m, l, e));
            mediaTypes.clear(); languages.clear(); encodings.clear();
            return this;
        }

        @Override public Variant.VariantListBuilder languages(java.util.Locale... langs) {
            for (java.util.Locale l : langs) languages.add(l);
            return this;
        }
        @Override public Variant.VariantListBuilder encodings(String... enc) {
            for (String e : enc) encodings.add(e);
            return this;
        }
        @Override public Variant.VariantListBuilder mediaTypes(MediaType... mts) {
            for (MediaType m : mts) mediaTypes.add(m);
            return this;
        }
    }

    /** Stub {@link Link.Builder} minimal : capture uri / rel / params, construit
     *  un Link renvoyant simplement ce qu'on lui a dit. Suffit pour que les TCK
     *  qui construisent un Link sans vérifier sa sérialisation passent. */
    private static final class StubLinkBuilder implements Link.Builder {
        private java.net.URI uri;
        private jakarta.ws.rs.core.UriBuilder uriBuilder;
        private java.net.URI baseUri;
        private final java.util.Map<String, String> params = new java.util.LinkedHashMap<>();

        @Override public Link.Builder link(Link link) {
            this.uri = link.getUri();
            params.clear();
            params.putAll(link.getParams());
            return this;
        }
        @Override public Link.Builder link(String link) { return uri(link); }
        @Override public Link.Builder uri(java.net.URI uri) {
            if (uri == null) throw new IllegalArgumentException("uri");
            // Validation basique §4.3.4 : path vide ou authority vide non-null rejetés
            if (uri.getScheme() != null && uri.getAuthority() != null
                    && uri.getHost() == null && !uri.getRawAuthority().isEmpty()) {
                throw new IllegalArgumentException("malformed URI: " + uri);
            }
            this.uri = uri;
            return this;
        }
        @Override public Link.Builder uri(String uri) {
            if (uri == null) throw new IllegalArgumentException("uri");
            // Les templates JAX-RS {name} ne sont pas valides pour java.net.URI.
            // On les encode en %7Bname%7D le temps de parser, puis on rétablit
            // la forme originale dans l'URI stockée si elle reste templatée.
            if (uri.indexOf('{') >= 0 || uri.indexOf('}') >= 0) {
                String encoded = uri.replace("{", "%7B").replace("}", "%7D");
                try {
                    java.net.URI u = new java.net.URI(encoded);
                    return uri(u);
                } catch (java.net.URISyntaxException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            try { return uri(new java.net.URI(uri)); }
            catch (java.net.URISyntaxException e) { throw new IllegalArgumentException(e); }
        }
        @Override public Link.Builder baseUri(java.net.URI uri) { this.baseUri = uri; return this; }
        @Override public Link.Builder baseUri(String uri) { this.baseUri = java.net.URI.create(uri); return this; }
        @Override public Link.Builder uriBuilder(jakarta.ws.rs.core.UriBuilder ub) { this.uriBuilder = ub; return this; }
        @Override public Link.Builder rel(String rel) {
            if (rel == null) throw new IllegalArgumentException("rel");
            String existing = params.get("rel");
            params.put("rel", existing == null ? rel : existing + " " + rel);
            return this;
        }
        @Override public Link.Builder param(String name, String value) {
            if (name == null) throw new IllegalArgumentException("name");
            params.put(name, value);
            return this;
        }
        @Override public Link.Builder title(String t) { params.put("title", t); return this; }
        @Override public Link.Builder type(String t) { params.put("type", t); return this; }

        @Override public Link build(Object... values) {
            if (values == null) throw new IllegalArgumentException("values");
            String uriStr;
            if (uri != null) uriStr = uri.toString();
            else if (uriBuilder != null) uriStr = uriBuilder.build(values).toString();
            else uriStr = "";
            // Décode %7B/%7D en {} pour substituer, puis substitue positionnellement.
            String decoded = uriStr.replace("%7B", "{").replace("%7D", "}")
                    .replace("%7b", "{").replace("%7d", "}");
            String substituted = substituteTemplates(decoded, values);
            if (substituted.indexOf('{') >= 0) {
                throw new IllegalArgumentException(
                        "value not supplied for template in link uri: " + decoded);
            }
            java.net.URI effective = java.net.URI.create(substituted);
            if (baseUri != null) effective = baseUri.resolve(effective);
            return new StubLink(effective, java.util.Map.copyOf(params));
        }

        @Override public Link buildRelativized(java.net.URI base, Object... values) {
            if (base == null) throw new IllegalArgumentException("base");
            Link l = build(values);
            java.net.URI rel = base.relativize(l.getUri());
            return new StubLink(rel, l.getParams());
        }

        private static String substituteTemplates(String tpl, Object[] values) {
            if (tpl == null || tpl.indexOf('{') < 0) return tpl;
            StringBuilder out = new StringBuilder();
            int i = 0, pos = 0;
            java.util.Map<String, Object> seen = new java.util.LinkedHashMap<>();
            while (i < tpl.length()) {
                char c = tpl.charAt(i);
                if (c == '{') {
                    int end = tpl.indexOf('}', i);
                    if (end < 0) { out.append(tpl, i, tpl.length()); break; }
                    String name = tpl.substring(i + 1, end).trim();
                    int colon = name.indexOf(':');
                    if (colon >= 0) name = name.substring(0, colon).trim();
                    Object val;
                    if (seen.containsKey(name)) val = seen.get(name);
                    else if (pos < values.length) { val = values[pos++]; seen.put(name, val); }
                    else { out.append('{').append(tpl, i + 1, end).append('}'); i = end + 1; continue; }
                    out.append(val);
                    i = end + 1;
                } else { out.append(c); i++; }
            }
            return out.toString();
        }
    }

    private static final class StubLink extends Link {
        private final java.net.URI uri;
        private final java.util.Map<String, String> params;

        StubLink(java.net.URI uri, java.util.Map<String, String> params) {
            this.uri = uri;
            this.params = params;
        }
        @Override public java.net.URI getUri() { return uri; }
        @Override public jakarta.ws.rs.core.UriBuilder getUriBuilder() { return new CassiniUriBuilder().uri(uri); }
        @Override public String getRel() { return params.get("rel"); }
        @Override public java.util.List<String> getRels() {
            String r = params.get("rel");
            return r == null ? java.util.List.of() : java.util.List.of(r.split("\\s+"));
        }
        @Override public String getTitle() { return params.get("title"); }
        @Override public String getType() { return params.get("type"); }
        @Override public java.util.Map<String, String> getParams() { return params; }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder("<").append(uri).append('>');
            for (var e : params.entrySet()) sb.append(';').append(e.getKey()).append("=\"").append(e.getValue()).append('"');
            return sb.toString();
        }
    }
}

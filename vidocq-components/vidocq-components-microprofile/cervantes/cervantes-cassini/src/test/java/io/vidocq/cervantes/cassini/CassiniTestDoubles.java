package io.vidocq.cervantes.cassini;

import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Doubles de test JAX-RS (sans Mockito) : juste ce qu'exigent les filtres/feature de Cervantes. */
final class CassiniTestDoubles {

    private CassiniTestDoubles() {}

    /** Construit un JsonWebToken de test (claims sub/upn/groups) via l'impl du core. */
    static org.eclipse.microprofile.jwt.JsonWebToken token(String subject, java.util.Set<String> groups) {
        jakarta.json.JsonArrayBuilder g = jakarta.json.Json.createArrayBuilder();
        groups.forEach(g::add);
        jakarta.json.JsonObject claims = jakarta.json.Json.createObjectBuilder()
                .add("sub", subject)
                .add("upn", subject)
                .add("groups", g)
                .build();
        return new io.vidocq.cervantes.internal.DefaultJsonWebToken(claims, "raw-" + subject);
    }

    static ResourceInfo resourceInfo(Class<?> resourceClass, Method method) {
        return new ResourceInfo() {
            @Override public Method getResourceMethod() { return method; }
            @Override public Class<?> getResourceClass() { return resourceClass; }
        };
    }

    /** {@link FeatureContext} qui capture les composants enregistrés. */
    static final class CapturingFeatureContext implements FeatureContext {
        final List<Object> registered = new ArrayList<>();

        @Override public FeatureContext register(Object component) { registered.add(component); return this; }
        @Override public FeatureContext register(Object component, int priority) { registered.add(component); return this; }
        @Override public FeatureContext register(Object component, Class<?>... contracts) { registered.add(component); return this; }
        @Override public FeatureContext register(Object component, Map<Class<?>, Integer> contracts) { registered.add(component); return this; }
        @Override public FeatureContext register(Class<?> componentClass) { registered.add(componentClass); return this; }
        @Override public FeatureContext register(Class<?> componentClass, int priority) { registered.add(componentClass); return this; }
        @Override public FeatureContext register(Class<?> componentClass, Class<?>... contracts) { registered.add(componentClass); return this; }
        @Override public FeatureContext register(Class<?> componentClass, Map<Class<?>, Integer> contracts) { registered.add(componentClass); return this; }
        @Override public FeatureContext property(String name, Object value) { return this; }
        @Override public Configuration getConfiguration() { throw new UnsupportedOperationException(); }
    }

    /** {@link jakarta.ws.rs.container.ContainerRequestContext} minimal : header Authorization, SecurityContext, abort. */
    static final class FakeRequestContext implements jakarta.ws.rs.container.ContainerRequestContext {
        private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        private SecurityContext securityContext;
        private Response abortedWith;

        FakeRequestContext authorization(String value) {
            if (value != null) headers.putSingle("Authorization", value);
            return this;
        }

        FakeRequestContext securityContext(SecurityContext sc) { this.securityContext = sc; return this; }

        boolean isAborted() { return abortedWith != null; }
        int abortedStatus() { return abortedWith == null ? -1 : abortedWith.getStatus(); }

        @Override public String getHeaderString(String name) { return headers.getFirst(name); }
        @Override public boolean containsHeaderString(String name, String valueSeparatorRegex, java.util.function.Predicate<String> valuePredicate) { throw new UnsupportedOperationException(); }
        @Override public boolean containsHeaderString(String name, java.util.function.Predicate<String> valuePredicate) { throw new UnsupportedOperationException(); }
        @Override public MultivaluedMap<String, String> getHeaders() { return headers; }
        @Override public SecurityContext getSecurityContext() { return securityContext; }
        @Override public void setSecurityContext(SecurityContext context) { this.securityContext = context; }
        @Override public void abortWith(Response response) { this.abortedWith = response; }

        // --- non utilisés ---
        @Override public Object getProperty(String name) { throw new UnsupportedOperationException(); }
        @Override public Collection<String> getPropertyNames() { throw new UnsupportedOperationException(); }
        @Override public void setProperty(String name, Object object) { throw new UnsupportedOperationException(); }
        @Override public void removeProperty(String name) { throw new UnsupportedOperationException(); }
        @Override public UriInfo getUriInfo() { throw new UnsupportedOperationException(); }
        @Override public void setRequestUri(URI requestUri) { throw new UnsupportedOperationException(); }
        @Override public void setRequestUri(URI baseUri, URI requestUri) { throw new UnsupportedOperationException(); }
        @Override public Request getRequest() { throw new UnsupportedOperationException(); }
        @Override public String getMethod() { throw new UnsupportedOperationException(); }
        @Override public void setMethod(String method) { throw new UnsupportedOperationException(); }
        @Override public Date getDate() { throw new UnsupportedOperationException(); }
        @Override public Locale getLanguage() { throw new UnsupportedOperationException(); }
        @Override public int getLength() { throw new UnsupportedOperationException(); }
        @Override public MediaType getMediaType() { throw new UnsupportedOperationException(); }
        @Override public List<MediaType> getAcceptableMediaTypes() { throw new UnsupportedOperationException(); }
        @Override public List<Locale> getAcceptableLanguages() { throw new UnsupportedOperationException(); }
        @Override public Map<String, jakarta.ws.rs.core.Cookie> getCookies() { throw new UnsupportedOperationException(); }
        @Override public boolean hasEntity() { throw new UnsupportedOperationException(); }
        @Override public InputStream getEntityStream() { throw new UnsupportedOperationException(); }
        @Override public void setEntityStream(InputStream input) { throw new UnsupportedOperationException(); }
    }
}

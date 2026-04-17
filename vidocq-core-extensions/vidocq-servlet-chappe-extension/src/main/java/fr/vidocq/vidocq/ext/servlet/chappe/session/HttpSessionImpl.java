package fr.vidocq.vidocq.ext.servlet.chappe.session;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;

import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Implémentation in-memory thread-safe de {@link HttpSession}.
 *
 * <p>Délègue la gestion d'expiration au {@link SessionManager} qui consulte
 * {@link #getLastAccessedTime()} et {@link #getMaxInactiveInterval()}.</p>
 */
public final class HttpSessionImpl implements HttpSession {

    private final String id;
    private final ServletContext servletContext;
    private final SessionManager manager;
    private final long creationTime;
    private volatile long lastAccessedTime;
    private volatile int maxInactiveInterval;
    private volatile boolean newSession = true;
    private volatile boolean invalidated;
    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();

    public HttpSessionImpl(String id, ServletContext ctx, SessionManager manager, int maxInactiveSeconds) {
        this.id = id;
        this.servletContext = ctx;
        this.manager = manager;
        this.creationTime = System.currentTimeMillis();
        this.lastAccessedTime = creationTime;
        this.maxInactiveInterval = maxInactiveSeconds;
    }

    void markAccessed() {
        if (invalidated) throw new IllegalStateException("session invalidated");
        this.lastAccessedTime = System.currentTimeMillis();
        this.newSession = false;
    }

    @Override public long getCreationTime() { checkValid(); return creationTime; }
    @Override public String getId() { return id; }
    @Override public long getLastAccessedTime() { checkValid(); return lastAccessedTime; }
    @Override public ServletContext getServletContext() { return servletContext; }
    @Override public void setMaxInactiveInterval(int interval) { this.maxInactiveInterval = interval; }
    @Override public int getMaxInactiveInterval() { return maxInactiveInterval; }

    @Override public Object getAttribute(String name) { checkValid(); return attributes.get(name); }
    @Override public Enumeration<String> getAttributeNames() {
        checkValid();
        return Collections.enumeration(attributes.keySet());
    }
    @Override public void setAttribute(String name, Object value) {
        checkValid();
        if (value == null) attributes.remove(name);
        else attributes.put(name, value);
    }
    @Override public void removeAttribute(String name) { checkValid(); attributes.remove(name); }

    @Override public void invalidate() {
        checkValid();
        invalidated = true;
        attributes.clear();
        manager.onInvalidate(id);
    }

    @Override public boolean isNew() { checkValid(); return newSession; }

    public boolean isInvalidated() { return invalidated; }

    private void checkValid() {
        if (invalidated) throw new IllegalStateException("session invalidated");
    }
}

package fr.vidocq.vidocq.ext.rest.cassini.internal.context;

import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;

import java.util.Date;
import java.util.List;

/**
 * Implémentation {@link Request} JAX-RS (pas la Request Chappe !).
 *
 * <p>M2d : seul {@link #getMethod()} est fonctionnel, les méthodes de
 * négociation de variant / pré-conditions ne sont pas encore implémentées
 * (prévues pour M2e où l'on fait le Response/ResponseBuilder complet).</p>
 */
public final class CassiniRequest implements Request {

    private final String method;

    public CassiniRequest(String method) {
        this.method = method;
    }

    @Override public String getMethod() { return method; }

    @Override public Variant selectVariant(List<Variant> variants) {
        throw new UnsupportedOperationException("selectVariant not yet implemented");
    }

    @Override public Response.ResponseBuilder evaluatePreconditions(EntityTag eTag) { return null; }
    @Override public Response.ResponseBuilder evaluatePreconditions(Date lastModified) { return null; }
    @Override public Response.ResponseBuilder evaluatePreconditions(Date lastModified, EntityTag eTag) { return null; }
    @Override public Response.ResponseBuilder evaluatePreconditions() { return null; }
}

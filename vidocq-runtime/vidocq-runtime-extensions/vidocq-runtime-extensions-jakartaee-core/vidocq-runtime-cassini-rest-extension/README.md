# vidocq-runtime-cassini-rest-extension

**Jakarta RESTful Web Services 4.0** implementation for Vidocq, mounted on the
in-house HTTP engine **Chappe** (via `ChappeMountPoint`).

## Cassini — where does the name come from?

The **Cassini** dynasty (1625–1845, four generations) produced the
**first complete map of France by geodesic triangulation** — every
point of the kingdom tied to a hierarchical network of triangles, then to roads, then to named places.

This is exactly what a JAX-RS router does:

| Cassini (cartography) | Cassini (runtime) |
|---|---|
| Geodesic triangles | URI templates `@Path("/a/{b}/c")` |
| Precision measurements | Exact match vs regex (`{id:\\d+}`) |
| Hierarchical mesh France → province → town | Root resource → sub-resource locator → sub-resource method |
| Reference meridian | `UriInfo.getBaseUri()` |
| Triangulation: unique point from 3 angles | Best-match: unique method by (path, verb, media-type) |

The REST resource tree is a **map**. Cassini is the runtime
that draws it and routes every request through it — and it does so over the signalling lines
traced by Chappe (the optical telegraph, HTTP transport).

## Status

| Milestone | Content | Status |
|---|---|---|
| Phase 0 | Module skeleton, POM, BCE `@RequestScoped` | Done |
| M1 | `ChappeMountPoint` bridge + minimal routing | Planned |
| M2a..j | Core JAX-RS (URI, params, body, @Context, Response, Filters, Features, Async, SSE, CDI) | Planned |
| M3 | Jakarta REST 4.0 TCK | Planned |

## Configuration

| Property | Default | Description |
|---|---|---|
| `vidocq.rest.context-path` | `/` | JAX-RS mount prefix |
| `vidocq.rest.listener` | `default` | Target Chappe listener |

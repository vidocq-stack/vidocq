# ADR-001: Ravel Integration Strategy in the Vidocq Ecosystem

**Date**: 2026-05-09  
**Status**: Accepted  
**Authors**: Vidocq Team

## Context

Ravel (MicroProfile Config 3.1 implementation) is now complete and TCK-compliant
(349/349 PASS). The M6 objective is to deploy Ravel in the existing Vidocq projects
(Cassini, Chappe, Vauban, vidocq) to replace Smallrye Config.

## Decision

### "Drop-in" integration strategy

Ravel replaces Smallrye Config as the `ConfigProviderResolver` implementation via
the standard ServiceLoader mechanism. No application code changes are required:

```
# Remove
io.smallrye.config:smallrye-config

# Add
io.vidocq.ravel:ravel-cdi-vauban  # with CDI (Cassini, Vauban)
io.vidocq.ravel:ravel-core        # without CDI (standalone Chappe)
```

### Deployment Order

1. **Vauban**: register `Config` as a CDI bean (via existing `ConfigCdiExtension`)
2. **Cassini**: `@ConfigProperty` injection in REST resources (via `cassini-cdi-vauban`)
3. **Chappe**: read server configuration via programmatic API `ConfigProvider`
4. **vidocq**: update the assembly to include `ravel-cdi-vauban`

### What is NOT changed

- The public APIs of the projects (JAX-RS, CDI, etc.) remain identical
- Existing `microprofile-config.properties` files are compatible
- `@ConfigProperty` and `@ConfigProperties` annotations remain identical
- Environment variables and system properties are read the same way

## Consequences

### Positive

- **Zero third-party dependency** in the configuration stack (Ravel is self-contained)
- **Improved JMH performance** on conversions (~20-37% depending on type)
- **Certified TCK 100% PASS** — guaranteed spec compliance
- **Strict Java Modules** — no encapsulation issues at runtime
- **Virtual threads friendly** — no `synchronized`, no `ThreadLocal`

### Identified Risks

| Risk | Mitigation |
|---|---|
| Subtle behavioural differences | TCK 349/349 PASS covers edge cases |
| Configuration expressions with Smallrye-specific behaviour | Document in `TCK.md` |
| Expression performance (63-88× slower than Smallrye) | Expression cache planned for M6+ |

## Alternatives Considered

1. **Smallrye wrapper**: inject adapters around Smallrye — rejected as it adds duality complexity.
2. **Smallrye fork**: start from Smallrye's code — rejected as it violates "zero third-party library".
3. **Progressive integration**: migrate module by module — accepted as the deployment plan.

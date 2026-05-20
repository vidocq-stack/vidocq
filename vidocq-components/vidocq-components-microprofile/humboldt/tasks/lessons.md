# Humboldt — Leçons de session

> Toute correction utilisateur ou validation non triviale doit être consignée ici. Calque sur `chappe/tasks/lessons.md`.

## M2 — SDK Trace (2026-05-20)

### Locale.ROOT pour les format strings

`String.format("%.6f", 0.42)` retourne `"0,420000"` dans un environnement français.
Les `description()` de Sampler / IdGenerator / etc. **doivent** utiliser
`String.format(Locale.ROOT, ...)` pour garantir un point décimal portable —
sinon les tests `contains("0.42")` échouent en CI selon la locale du runner.

### InMemorySpanExporter.shutdown() ne purge pas

Convention Humboldt : `shutdown()` arrête l'export mais **conserve** les
spans déjà collectés, pour permettre l'inspection après `provider.close()`
dans les tests en try-with-resources. Utiliser `reset()` pour vider
explicitement. Diffère de l'impl OTel de référence qui clear sur shutdown.

### SpanBuilder OTel 1.39 — 4 setAttribute primitifs abstract

Dans OpenTelemetry API 1.39.0, `SpanBuilder.setAttribute(String, boolean)`
est ABSTRACT (et probablement les 3 autres primitifs). Il faut overrider
les 4 par délégation `setAttribute(AttributeKey.{string|long|double|boolean}Key(key), value)`.
Pareil pour `TracerProvider.get(String name, String version)` qui doit
être implémenté (la simplification "cache by name only" est OK pour M2).

# Humboldt — Plan de développement

> Vue synthétique pour suivre l'avancement des milestones. Le plan détaillé est dans `PLAN.md` et la liste des jalons dans `ROADMAP.md`.

## M0 — Squelette JPMS et tooling _(terminé 2026-05-20)_

- [x] PLAN.md déposé (1190 lignes, validé par Yann le 2026-05-20)
- [x] `.sdkmanrc`, `mvnw`, `pom.xml` parent Model 4.1.0, `.gitignore`, LICENSE
- [x] `humboldt-api/` : `pom.xml`, `module-info.java`, façade `Humboldt`, SPI stubs (SpanExporterProvider, MetricReaderProvider, LogRecordExporterProvider, SamplerProvider, ResourceProvider), test JUnit
- [x] `.forgejo/workflows/` : ci.yml, pr.yml, notify-slack.yml, update-dep-graph.yml, upstream-pr.yml
- [x] `docs/{en,fr}/antora.yml` + `modules/ROOT/{nav,pages/index}.adoc` avec métaphore Humboldt
- [x] CLAUDE.md, README.md (FR), README_EN.md (EN), BUG.md, BENCH.md, TCK.md, ROADMAP.md
- [x] `git init` + premier commit signé Yann Blazart (`d28de83`)
- [x] Build de vérification `mvn verify` — **2/2 tests PASS, BUILD SUCCESS** (1.5s)

## M1 — Common + Context (Virtual-Threads) _(en cours)_

- [x] `humboldt-sdk-common` : `pom.xml`, `module-info.java`, `Clock` (system), `IdGenerator.Random128` (traceId 32 hex / spanId 16 hex via `ThreadLocalRandom`, jamais tout-zéro), `Resource` (immutable, equals/hashCode, merge OTel-style)
- [x] `humboldt-sdk-common` tests : ClockTest (3), IdGeneratorTest (4 — uniqueness sur 10k itérations), ResourceTest (7)
- [x] `humboldt-context` : `pom.xml`, `module-info.java` avec `provides ContextStorageProvider with HumboldtContextStorageProvider`, `META-INF/services` fallback classpath
- [x] `HumboldtContextStorage` ThreadLocal-backed, conforme contrat OTel (attach/close/current/root), log WARNING sur attach/detach désordonné, close idempotent
- [x] Tests : provider ServiceLoader, current()=root, attach/close, nested LIFO, isolation VT (sans wrap), propagation VT (avec `Context.wrap()`), close idempotent
- [x] pom parent : ajout `<subprojects>` + dependencyManagement entries pour humboldt-sdk-common et humboldt-context
- [ ] Build verify reactor complet, commit M1

## M2 — SDK Trace (prochain)

À démarrer après M1 verte. Voir `PLAN.md` §13 et `ROADMAP.md`.

## Leçons en cours de session

À documenter au fur et à mesure dans `tasks/lessons.md`.

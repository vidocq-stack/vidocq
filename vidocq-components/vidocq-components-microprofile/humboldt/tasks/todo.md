# Humboldt — Plan de développement

> Vue synthétique pour suivre l'avancement des milestones. Le plan détaillé est dans `PLAN.md` et la liste des jalons dans `ROADMAP.md`.

## M0 — Squelette JPMS et tooling _(en cours)_

- [x] PLAN.md déposé (1190 lignes, validé par Yann le 2026-05-20)
- [x] `.sdkmanrc`, `mvnw`, `pom.xml` parent Model 4.1.0, `.gitignore`, LICENSE
- [x] `humboldt-api/` : `pom.xml`, `module-info.java`, façade `Humboldt`, SPI stubs (SpanExporterProvider, MetricReaderProvider, LogRecordExporterProvider, SamplerProvider, ResourceProvider), test JUnit
- [x] `.forgejo/workflows/` : ci.yml, pr.yml, notify-slack.yml, update-dep-graph.yml, upstream-pr.yml
- [x] `docs/{en,fr}/antora.yml` + `modules/ROOT/{nav,pages/index}.adoc` avec métaphore Humboldt
- [x] CLAUDE.md, README.md (FR), README_EN.md (EN), BUG.md, BENCH.md, TCK.md, ROADMAP.md
- [ ] `git init` + premier commit signé Yann Blazart (sans Co-Authored-By)
- [ ] Build de vérification `./mvnw -ntp install -DskipTests`

## M1 — Common + Context (Virtual-Threads)

À démarrer après validation M0. Voir `PLAN.md` §13 jalons et `ROADMAP.md`.

## Leçons en cours de session

À documenter au fur et à mesure dans `tasks/lessons.md`.

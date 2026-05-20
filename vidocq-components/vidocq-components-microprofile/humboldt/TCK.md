# Humboldt — Statut TCK MicroProfile Telemetry 2.1

## Cible

100 % de conformité TCK officiel `org.eclipse.microprofile.telemetry:microprofile-telemetry-tck:2.1` sur la suite complète : tracing, metrics, logs, baggage, propagation W3C, configuration `OTEL_*` / `MP_TELEMETRY_*`.

## Statut courant

**M0 — Squelette JPMS.** Le runner TCK (`humboldt-tck`) sera créé en M7 selon la roadmap (cf. [`ROADMAP.md`](ROADMAP.md) et [`PLAN.md`](PLAN.md) §11).

| Signal | Statut | Cible jalons |
|---|---|---|
| Tracing | 🚧 non démarré | M3 (PASS partiel) → M7 (PASS complet) |
| Metrics | 🚧 non démarré | M4 (PASS partiel) → M7 (PASS complet) |
| Logs | 🚧 non démarré | M5 (PASS partiel) → M7 (PASS complet) |
| Baggage | 🚧 non démarré | M3 (W3C propagator) |
| Config | 🚧 non démarré | M2 (`HumboldtConfig` via Ravel) |

## Contrainte d'architecture

Comme `cassini-tck`, `champollion-tck`, `foy-tck` : **`humboldt-tck` sera hors reactor** (POM Model 4.0.0 standalone, sans `<parent>`), pour contourner l'incompatibilité ShrinkWrap Maven Resolver 3.3 / Model 4.1.0. Ne pas réintégrer au reactor. Voir CLAUDE.md racine du workspace pour le détail de la contrainte.

## Challenges TCK (format)

Quand un test TCK officiel est désactivé pour cause d'interprétation spec non-portable, environnement TCK douteux, ou pour une raison hors scope momentané, documenter le challenge ici dans ce format :

```
### [TCK-N] NomDuTest
- **Catégorie** : interprétation-spec / environnement-tck / hors-scope
- **Date** : YYYY-MM-DD
- **Justification** : pourquoi le test est désactivé / contesté
- **Action upstream** : issue ouverte ? PR proposée ? lien
- **Quand le réactiver** : condition de réactivation
```

---

_Aucun challenge à ce jour (M0)._

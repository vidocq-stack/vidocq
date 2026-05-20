# Humboldt — Benchmarks

> Tout chiffre de performance (JMH, wrk, comparatif vs SmallRye Telemetry / OTel SDK Java, etc.) doit être consigné ici. Pas de chiffre dans un README ou un commit message sans entrée correspondante. Cf. `CLAUDE.md` racine du workspace pour la convention.

## Format d'une entrée

```
### [HBT-BNCH-N] Titre court
- **Date** : YYYY-MM-DD
- **Hardware** : modèle CPU, RAM, OS
- **JVM** : Java 25 Temurin / GraalVM CE 24 / …
- **Commit** : SHA Humboldt + SHA des dépendances comparées
- **Outil** : JMH / wrk / custom
- **Commande exacte** : copier-coller reproductible
- **Résultats bruts** : tableau ops/s, latence p50/p99, allocations, etc.
- **Delta vs run précédent** : %
- **Analyse** : interprétation, points chauds, ADR à créer
```

---

_Aucun benchmark exécuté à ce jour (M0)._

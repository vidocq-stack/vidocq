# Run TCK MP Telemetry 2.1 — post M5b (JUL bridge + LoggingLogRecordExporter)

**Date** : 2026-05-23 20:53
**Commande** : `./run-official-tck-telemetry-2.1.sh all`
**Durée** : ~3 min

## Résultat

```
<testng-results ignored="0" total="85" passed="9" failed="48" skipped="28">
```

## Évolution

| Run | Date | total | PASS | FAIL | SKIP |
|---|---|---|---|---|---|
| post M7c.1+2+4 | 2026-05-23 00:36 | 93 | 5 | 60 | 28 |
| post M7c.8 | 2026-05-23 20:32 | 85 | 7 | 50 | 28 |
| **post M5b** | 2026-05-23 20:53 | **85** | **9** | **48** | **28** |

Delta M5b net : **+2 PASS, -2 FAIL**.

## Items livrés en M5b

- **`HumboldtJulHandler`** (humboldt-sdk-log/bridge) : bridge JUL → OTel Logger.
  Mapping severity (SEVERE→ERROR, WARNING→WARN, INFO→INFO, CONFIG/FINE→DEBUG,
  FINER→DEBUG2, FINEST→DEBUG3). Cache de Logger par scope name. Idempotent.
- **`LoggingLogRecordExporter`** (humboldt-sdk-log/export) : exporter file-based
  conforme au format attendu par MP Telemetry Logs TCK :
  ```
  YYYY-MM-DD HH:MM:SS.SSS LEVEL <body> scopeInfo:<scope>:<version>
  ```
  Path résolu via system property `mptelemetry.tck.log.file.path`, fallback stdout.
- **Câblage `HumboldtAutoConfigure`** :
  - case `OTEL_LOGS_EXPORTER=logging` → `LoggingLogRecordExporter.create()`
  - `SimpleLogRecordProcessor` (synchrone) pour `in-memory` ET `logging` (le TCK lit
    le fichier immédiatement, pas de flush batch différé)
  - Auto-install du `HumboldtJulHandler` sur root JUL logger quand le pipeline logs
    est en mode production (otlp ou logging) — **pas en in-memory** pour éviter que
    les logs internes du runtime polluent les InMemoryLogRecordExporter des tests
  - Idempotent : ne réinstalle pas si un `HumboldtJulHandler` déjà présent
- **POM `humboldt-tck`** : `systemPropertyVariables` ajouté au profile `tck-official`
  pour passer `mptelemetry.tck.log.file.path=${project.build.directory}/humboldt-tck-logs.txt`

## Tests confirmés PASS

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 (JulTest)
```

- ✅ `JulTest.julInfoTest` — log JUL INFO appears in file with right format
- ✅ `JulTest.julWarnTest` — log JUL WARNING (mapped WARN) appears

Exemple de sortie générée (`target/humboldt-tck-logs.txt`) :
```
2026-05-23 18:52:59.084 INFO a very distinguishable info message scopeInfo:jul-logger:
2026-05-23 18:53:04.102 WARN a very distinguishable warning message scopeInfo:jul-logger:
```

## Régression évitée

- Test unit `HumboldtAutoConfigureTest.in_memory_pipeline_exports_traces_metrics_logs_end_to_end`
  initialement KO car le JulHandler interceptait le `LOG.log(...)` de fin d'init
  et émettait 2 records au lieu de 1. Fix : install conditionnel
  (`logging` ou `otlp` uniquement, pas `in-memory` ni `none`).

## Prochaines étapes (ordre par impact restant)

| Step | Item | Gain estimé | Effort |
|---|---|---|---|
| 1 | **M7c.9** Injection JAX-RS @Context dans CassiniHarness | +5-8 | Moyen |
| 2 | **M7c.7** ClientFilter JAX-RS (CLIENT spans) | +6 | Moyen |
| 3 | **M7c.5** Cyrano MP Rest Client integration | +12-18 | Moyen-gros |
| 4 | **M4b** SDK metric complet | +24 | Gros |
| 5 | Cluster D autoconfigure SPI (déféré ?) | +3 | À arbitrer |

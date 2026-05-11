# BUG — Knock

Suivi des bugs reproductibles dans `knock` (issues internes, régressions, comportements
incorrects non encore corrigés). Convention workspace Vidocq : id court, date, symptôme,
repro minimal, hypothèse de cause, statut.

---

## BUG-001 — `tck-mp-health` plante : `cassini-tck:0.1.0-SNAPSHOT` introuvable

- **Date ouverture** : 2026-05-10
- **Date résolution** : 2026-05-11
- **Statut** : ✅ RÉSOLU

### Symptôme

Job `CI / tck-mp-health` du `ci.yml` de knock échoue systématiquement à la phase de
résolution Maven :

```
[ERROR] Failed to execute goal on project knock-tck:
  Could not resolve dependencies for project io.vidocq.knock:knock-tck:jar:0.1.0-SNAPSHOT:
  The following artifacts could not be resolved:
    io.vidocq.cassini:cassini-tck:jar:0.1.0-SNAPSHOT (absent)
  → vidocq-snapshots: HTTP 404
```

Côté run Forgejo : statut `CI / tck-mp-health (push) | failure | Failing after 37s` sur
tous les push `main` antérieurs à 2026-05-11.

### Repro minimal

```bash
cd ~/projects/perso/vidocq/knock
./mvnw -ntp -pl knock-api,knock-core,knock-cdi-vauban,knock-cassini -am install -DskipTests
./run-official-tck-mp-health-4.0.sh all
# → fail à la phase "mvn test -f knock-tck/pom.xml"
```

### Cause

`knock-tck/pom.xml` (ligne 263) déclare en `<scope>test</scope>` :

```xml
<dependency>
  <groupId>io.vidocq.cassini</groupId>
  <artifactId>cassini-tck</artifactId>
  <version>${cassini.version}</version>
  <scope>test</scope>
</dependency>
```

Or `cassini-tck` est lui-même un module **hors reactor** de cassini (POM Model 4.0.0
standalone, contrainte ShrinkWrap Maven Resolver vs Model 4.1). Le `mvn deploy` du
reactor cassini **ne le couvrait pas** → jamais publié sur Reposilite → résolution KO
côté knock.

### Workaround temporaire (avant fix)

`deploy needs: build` (et **non** `needs: tck-mp-health`) dans `ci.yml` de knock pour
permettre la publication des snapshots knock malgré le TCK rouge — knock devait rester
consommable par `vidocq-mps` même tant que le TCK plantait.

### Fix appliqué

**Commit** : `cassini@1f9e6000` — ajout d'un step dédié au `ci.yml` de cassini, après
le step `Deploy to Forgejo Maven registry` :

```yaml
- name: Build et deploy cassini-tck (hors reactor)
  run: |
    mvn -B -ntp -f cassini-tck/pom.xml \
        deploy -Dmaven.test.skip=true \
        -DaltDeploymentRepository=vidocq-snapshots::https://repo.vidocq.dev/snapshots
```

Points clés :

- `-f cassini-tck/pom.xml` pointe sur le POM standalone (Model 4.0.0 reste inchangé)
- `-Dmaven.test.skip=true` (pas `-DskipTests`) évite la résolution des deps test, dont
  `jakarta-restful-ws-tck` (artefact Jakarta non-public)
- `-DaltDeploymentRepository=id::url` redirige le deploy vers Reposilite sans modifier
  le pom standalone (qui n'a pas de `<distributionManagement>`)

**Deuxième commit** : `knock@6086572a` — rétablit `deploy needs: [build, tck-mp-health]`
dans le `ci.yml` de knock (gate de release sur TCK PASS à 100%).

### Validation

Pipeline complet `knock@6086572a` (2026-05-11 17:16-17:17) :

| Job | Statut | Durée |
| --- | --- | --- |
| `build` | ✅ | 16s |
| `tck-mp-health` | ✅ TCK PASS 100% | 33s |
| `deploy` | ✅ (exécuté après tck-mp-health) | 17s |

Total : 1m06s. Le gate `deploy needs: [build, tck-mp-health]` est respecté.

### Référence externe

- `Forge-New/99-Annexes/Gotchas-Java-Maven.md` §2 (recette générique des TCK hors reactor)
- `Forge-New/60-CICD-Cross-Repo/Workflow-ci.yml.md` (section "Publication des TCK out-of-reactor")
- `cassini/.forgejo/workflows/ci.yml` (step ajouté ligne ~71)

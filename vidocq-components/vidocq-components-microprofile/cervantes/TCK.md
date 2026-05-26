# TCK.md — Cervantes (MicroProfile JWT 2.1)

## Artefact

Framework : **Arquillian** (le TCK MP JWT est piloté par Arquillian + REST, contrairement au TCK
MP FT qui est TestNG-only). Le container démarre la pile Vidocq complète
(chappe + cassini + vauban + cervantes) pour servir des endpoints protégés et leur présenter des
tokens forgés.

Artefact officiel (non-public) à installer dans le M2 local :

```
org.eclipse.microprofile.jwt:microprofile-jwt-auth-tck:2.1
```

Procédure d'installation détaillée dans `cervantes-tck/README.md` (à créer au jalon M6).

## Lancement

```bash
./run-official-tck-mp-jwt-2.1.sh            # smoke test
./run-official-tck-mp-jwt-2.1.sh all        # suite complète
./run-official-tck-mp-jwt-2.1.sh -Dtest=... # test ciblé
```

> `cervantes-tck` est **hors reactor** (Model 4.0.0 standalone, sans `<parent>`) — contrainte
> ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0. Toujours passer par le script, jamais `mvn -pl`.

## Score & exclusions

| Date | PASS | FAIL | SKIP | Note |
|------|------|------|------|------|
| —    | —    | —    | —    | non lancé (M6) |

Toute exclusion de test sera documentée ici avec sa justification (interprétation de spec
non-portable, limitation d'environnement, etc.).

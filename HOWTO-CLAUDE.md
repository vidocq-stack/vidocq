# HOWTO — Comment j'ai utilisé Claude Code pour Vidocq

> Retour d'expérience sur la collaboration avec **Claude Code (Opus 4.7 / 1M)**
> pour construire `vidocq-servlet-chappe-extension` et passer **0 → 99,2 %
> du TCK officiel Jakarta Servlet 6.1** (608/613) en ~60 commits.

---

## 1. Philosophie de collaboration

Claude n'est ni un copilote de snippet ni un oracle : c'est un **pair-programmeur
autonome** à qui on confie des jalons (milestones) et qui rend un diff + un
commit. Mon rôle : cadrer, arbitrer, valider. Son rôle : explorer, coder,
décompiler, tester, réessayer.

Règles que j'ai posées (et qui ont tenu) :

- **Un commit = un jalon fonctionnel** (ex. `M2b filtres`, `M2c sessions`, …).
  Jamais de "WIP" ou de commit fourre-tout.
- **Pas de raccourci destructif** (`--no-verify`, `git reset --hard`, …) sans
  autorisation explicite.
- **Root cause avant workaround** : quand un test TCK échoue, comprendre
  *pourquoi* avant de tricher le code.
- **Français partout** (messages de commit, code, docs) — imposé via
  `~/.claude/CLAUDE.md`.

---

## 2. Configuration Claude Code

### 2.1 Permissions ciblées (`.claude/settings.local.json`)

Plutôt que d'autoriser `Bash(*)`, j'ai **whitelisté au fil de l'eau** les
commandes utiles :

```json
{
  "permissions": {
    "allow": [
      "Bash(git reset:*)",
      "Bash(git commit:*)",
      "Bash(git add:*)",
      "Bash(jar tf *)",
      "Bash(javap -p -c /tmp/*.class)",
      "Bash(unzip -p /Users/yblazart/.m2/repository/jakarta/tck/...)",
      "mcp__plugin_context-mode_context-mode__ctx_batch_execute",
      "mcp__plugin_context-mode_context-mode__ctx_search"
    ]
  }
}
```

**Pourquoi ça marche** : Claude décompile les classes du TCK (`javap -c`) pour
comprendre ce que le test attend côté serveur. Je l'autorise à le faire sans
prompt à chaque fois, **mais uniquement dans `/tmp`** et **uniquement sur les
JARs TCK**. Pas de `Bash(rm *)`, pas de `Bash(curl *)`.

### 2.2 Instructions globales (`~/.claude/CLAUDE.md`)

- **Langue** : français obligatoire, accents conservés.
- **Ton** : concis, pas de narration interne, pas de résumé de fin de tour.
- **RTK** (Rust Token Killer) : proxy CLI qui réécrit les commandes git/mvn
  pour économiser 60-90 % de tokens en sortie.

### 2.3 Context-mode MCP

Tous les outils qui produisent >20 lignes (logs Maven, sortie `javap`,
rapports TCK) passent par `ctx_batch_execute` / `ctx_execute_file`. Le résultat
reste dans un sandbox indexé FTS5 ; Claude ne récupère que les lignes qu'il
cherche via `ctx_search`. Sans ça, un seul `mvn test` du TCK officiel remplit
le contexte à lui seul.

---

## 3. Méthodologie TCK-driven

La ligne directrice des 50 derniers commits :

```
1. Lance le TCK officiel → récupère la liste des tests en échec
2. Pour chaque test échoué :
   a. `unzip -p servlet-tck-runtime.jar <TestClass>.class > /tmp/t.class`
   b. `javap -p -c /tmp/t.class` → lit le bytecode du test
   c. Identifie l'attente précise (status code, header, side-effect)
   d. Corrige l'implémentation Chappe correspondante
   e. Relance UNIQUEMENT la classe de test ciblée (`-Dtest=...`)
3. Une fois un groupe cohérent passé → commit avec le delta chiffré
   ("TCK Servlet 6.1 — 96,6 % → 98,5 % (+12 tests, total 604/613)")
```

Cette boucle est **entièrement pilotée par Claude**. Mon intervention se
limite à :
- dire quel package TCK attaquer ensuite (`servletcontext30`, `cookie`, …),
- arbitrer quand l'implémentation diverge du JSR (ex. `Max-Age=0` sur Cookie),
- valider les commits.

---

## 4. Mémoire persistante

Claude maintient `~/.claude/projects/.../memory/MEMORY.md` où sont stockés :

- **user** : mon profil (Java 25 / CDI expert, rigoriste sur la spec).
- **feedback** : corrections appliquées une fois (ex. "ne mock jamais la
  ServletContext, utilise Chappe en vrai"), réutilisées ensuite.
- **project** : pourquoi Chappe existe, pourquoi le TCK runner est hors reactor
  (ShrinkWrap + Maven 4.1 incompatible), décisions d'archi.
- **reference** : chemin du JAR TCK, script de lancement, etc.

Résultat concret : je relance une session trois jours plus tard, Claude sait
déjà **où en est le TCK**, **quels bugs Vauban sont connus** (cf.
`VAUBAN-BUGS.md`), et **quelle convention de commit** utiliser.

---

## 5. Outils externes branchés

| Outil | Rôle | Gain |
|---|---|---|
| **RTK** | Proxy CLI qui filtre git/mvn | -60 à -90 % de tokens sortie |
| **context-mode MCP** | Sandbox + index FTS5 pour grosses sorties | Permet le TCK complet |
| **ctx_fetch_and_index** | Remplace WebFetch pour la spec Servlet/Jersey | Lecture ciblée |
| **Subagents (Explore, Plan)** | Exploration parallèle du code | Contexte principal préservé |

---

## 6. Ce qui a *vraiment* fait la différence

1. **Laisser Claude lire le bytecode du TCK.** C'est la seule source de vérité
   fiable — la doc Jakarta est incomplète, Tomcat diverge sur des détails.
2. **Commits granulaires en français avec métrique chiffrée.** Force à finir
   un jalon avant d'en commencer un autre, évite le "grand refactor" qui
   casse 40 tests d'un coup.
3. **Whitelist Bash évolutive.** Chaque nouvelle permission est une décision
   consciente — pas de `Bash(*)` paresseux.
4. **Context-mode systématique.** Sans ça, le TCK noie le contexte en 2 runs.
5. **Mémoire active.** Les bugs Vauban (`#1` à `#6`) identifiés par Claude ont
   été remontés upstream avec le diagnostic bytecode complet.

---

## 7. Ce que je ne fais PAS

- ❌ Demander à Claude de "faire passer le TCK" en une fois. Toujours par
  paquets de 5-15 tests.
- ❌ Utiliser `--dangerously-skip-permissions`. Les prompts de permission
  sont un signal : si Claude demande une commande que je n'avais pas prévue,
  c'est qu'il y a une piste que je dois comprendre.
- ❌ Laisser Claude écrire de la doc ou des README spontanément. Seulement
  sur demande explicite (comme ce fichier).
- ❌ Mélanger plusieurs jalons dans une conversation. `/clear` entre chaque
  milestone majeur — la mémoire persistante prend le relais.

---

## 8. Résultat chiffré

- **63 commits** sur `main`, tous signés et datés.
- **0 → 99,2 % du TCK Servlet 6.1 officiel** (608/613).
- **6 bugs Vauban** identifiés, documentés bytecode à l'appui, corrigés
  upstream (cf. `VAUBAN-BUGS.md`).
- **~48 ms** de démarrage pour l'exemple servlet avec 3 servlets + filter +
  listener, sur JDK 25 + Vauban CDI Lite.

---

*Fichier rédigé par Claude sur demande — relu et validé par Yann Blazart.*

# ADR-001 : Stratégie d'intégration Ravel dans l'écosystème Vidocq

**Date** : 2026-05-09  
**Statut** : Accepté  
**Auteurs** : Équipe Vidocq

## Contexte

Ravel (implémentation MicroProfile Config 3.1) est maintenant complet et TCK-compliant
(349/349 PASS). L'objectif M6 est de déployer Ravel dans les projets Vidocq existants
(Cassini, Chappe, Vauban, vidocq-mps) pour remplacer Smallrye Config.

## Décision

### Stratégie d'intégration « drop-in »

Ravel remplace Smallrye Config comme implémentation `ConfigProviderResolver` via
le mécanisme standard ServiceLoader. Aucune modification du code applicatif n'est requise :

```
# Supprimez
io.smallrye.config:smallrye-config

# Ajoutez
io.vidocq.ravel:ravel-cdi-vauban  # avec CDI (Cassini, Vauban)
io.vidocq.ravel:ravel-core        # sans CDI (Chappe standalone)
```

### Ordre de déploiement

1. **Vauban** : enregistrement de `Config` comme bean CDI (via `ConfigCdiExtension` existant)
2. **Cassini** : injection `@ConfigProperty` dans les ressources REST (via `cassini-cdi-vauban`)
3. **Chappe** : lecture de configuration du serveur via API programmatique `ConfigProvider`
4. **vidocq-mps** : mise à jour de l'assembly pour inclure `ravel-cdi-vauban`

### Ce qui N'EST PAS modifié

- Les APIs publiques des projets (JAX-RS, CDI, etc.) restent identiques
- Les fichiers `microprofile-config.properties` existants sont compatibles
- Les annotations `@ConfigProperty` et `@ConfigProperties` restent identiques
- Les variables d'environnement et propriétés système sont lues de la même manière

## Conséquences

### Positives

- **Zéro dépendance tierce** dans la pile de configuration (Ravel est autocontenu)
- **Performance JMH améliorée** sur les conversions (~20-37% selon le type)
- **TCK 100 % PASS** certifié — conformité spec garantie
- **JPMS strict** — pas de problèmes d'encapsulation au runtime
- **Virtual threads friendly** — pas de `synchronized`, pas de `ThreadLocal`

### Risques identifiés

| Risque | Mitigation |
|---|---|
| Différences de comportement subtiles | TCK 349/349 PASS couvre les edge cases |
| Expressions de configuration avec comportements Smallrye-specific | Documenter dans `TCK.md` |
| Performances des expressions (63-88× plus lentes que Smallrye) | Cache d'expressions prévu en M6+ |

## Alternatives considérées

1. **Wrapper Smallrye** : injecter des adapteurs autour de Smallrye — rejeté car ajoute la complexité de la dualité.
2. **Fork Smallrye** : partir du code Smallrye — rejeté car viole « zéro librairie tierce ».
3. **Intégration progressive** : migrer module par module — accepté comme plan de déploiement.


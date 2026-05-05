# mansart-data-core

Runtime Mansart Data : bootstrap standalone, exécution des plans de requête, mapping ResultSet ↔ entité, dispatch des appels de repository, **génération de bytecode pour les implémentations de `@Repository` à runtime via Class-File API (JEP 484)**.

## Points d'entrée publics

| Classe | Rôle |
| --- | --- |
| `MansartData` | Bootstrap standalone. `MansartData.builder().dataSource(ds).build()`. Voir README racine. |
| `MansartData.Builder` | Builder fluent. `dataSource(DataSource)` requis ; `dialect(Dialect)` optionnel. |
| `RepositoryRuntime` | Exécuteur des opérations CRUD/JDQL. Détenu par `MansartData` ou produit par CDI. |
| `MansartDataException` | Exception générique du module. |
| `MansartCallback` | Holder de dispatch utilisé par les classes générées. **Ne pas instancier directement**. |

## Flux d'un appel de repository

```
caller (virtual thread)
   ↓
HiddenClass <Repo>$$MansartImpl/0xNNNN.method(args)   ← bytecode généré
   ↓
MansartCallback.dispatch(methodIndex, Object[])
   ↓
Dispatcher (jdql / find / lifecycle / inherited / derived)
   ↓
RepositoryRuntime.<op>(EntityModel, args…)
   ↓
ConnectionScope (ScopedValue<Connection>)
   ↓
Dialect.{select,insert,update,delete,merge,paginate,bind,extract}
   ↓
JDBC PreparedStatement
```

## Génération bytecode runtime (M7-25)

`RuntimeRepositoryClassGenerator` produit, pour chaque interface `@Repository`, une classe **hidden** (`MethodHandles.Lookup#defineHiddenClass(bytes, true)`) qui implémente l'interface. Chaque méthode abstraite devient un stub minimal :

1. pack des arguments dans un `Object[]` (avec boxing des primitifs),
2. appel `callback.dispatch(methodIndex, args)`,
3. unboxing / cast du retour selon le type déclaré.

Aucune utilisation de `java.lang.reflect.Proxy`, ASM, Byte Buddy ou cglib. Le générateur n'utilise que `java.lang.classfile`, `java.lang.invoke.MethodHandles` et `java.lang.constant.*`.

Compatible AOT (GraalVM, Leyden CDS) — les classes générées sont des hidden classes scopées au package de l'interface, elles ne fuitent pas dans le ClassLoader global.

## JDQL

`JdqlAst` + `JdqlExecutor` implémentent un sous-ensemble de JDQL (Jakarta Data Query Language) : `SELECT` / `FROM` / `WHERE` / `GROUP BY` / `HAVING` / `ORDER BY` / `UPDATE` / `DELETE`. Voir le README racine pour la liste exhaustive des opérateurs.

## Module JPMS

```java
module io.vidocq.mansart.data.core {
    requires io.vidocq.mansart.data.api;
    requires io.vidocq.mansart.data.dialect.spi;
    requires java.sql;
    requires jakarta.data;

    exports io.vidocq.mansart.data.core;
    uses io.vidocq.mansart.data.dialect.DialectFactory;
}
```

## Dépendances runtime

- `mansart-data-api`
- `mansart-data-dialect-spi`
- `jakarta.data-api`
- `java.sql` (JDK)

Pas de driver JDBC en dépendance directe (l'application les apporte) ; pas de jar Jakarta CDI (le bootstrap CDI vit dans `mansart-data-cdi`).

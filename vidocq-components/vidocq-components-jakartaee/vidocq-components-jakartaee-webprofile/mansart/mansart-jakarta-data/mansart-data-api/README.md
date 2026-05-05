# mansart-data-api

Annotations Mansart **zéro-dep** pour décrire une entité : miroir minimal des annotations `jakarta.persistence` (mêmes noms simples, mêmes sémantiques principales). À utiliser à la place de JPA quand on ne veut pas tirer le jar `jakarta.persistence-api` côté entités.

## Annotations exportées

| Annotation | Cible | Notes |
| --- | --- | --- |
| `@Entity` | `TYPE` | Marque une classe persistante. |
| `@Table(name, schema)` | `TYPE` | Surcharge la convention pluriel snake_case. |
| `@Id` | `FIELD` | Identifiant. Multiples `@Id` interdits en v1. |
| `@GeneratedValue(strategy)` | `FIELD` | `AUTO`, `IDENTITY`, `SEQUENCE` (`GenerationType`). |
| `@Column(name, nullable, unique, length)` | `FIELD` | Surcharge la convention snake_case. |
| `@Version` | `FIELD` | Optimistic locking. |
| `@Enumerated(STRING\|ORDINAL)` | `FIELD` | `EnumType`. |
| `@Transient` | `FIELD` | Exclu du mapping. |
| `@ManyToOne(fetch)` | `FIELD` | `FetchType.EAGER\|LAZY`. |
| `@OneToOne(fetch)` | `FIELD` | idem. |
| `@JoinColumn(name)` | `FIELD` | Nom de la colonne FK (défaut `<attr>_id`). |
| `@Embedded` / `@Embeddable` | `FIELD` / `TYPE` | Mapping inline. |
| `@MansartDataSource("name")` | `TYPE` | Sélection de DataSource côté repository. |

## Règle JPA vs Mansart

Si `@jakarta.persistence.Entity` **et** `@io.vidocq.mansart.data.Entity` sont posées sur la même classe → erreur de compilation (APT). Choisir un seul jeu.

## Module JPMS

```java
module io.vidocq.mansart.data.api {
    exports io.vidocq.mansart.data;
}
```

## Dépendances

Aucune. Pas même `jakarta.persistence-api`.

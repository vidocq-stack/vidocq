/**
 * Implémentation MicroProfile Config 3.1 standalone — utilisable en SE pur,
 * sans CDI ni container. Les sources, converters et resolver seront contribués
 * via ServiceLoader (cf. ROADMAP.md §M1).
 */
module io.vidocq.ravel.core {
    requires transitive io.vidocq.ravel.api;
}

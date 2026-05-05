package io.vidocq.mansart.data.dialect.attribute;

/**
 * How an enum-typed attribute is persisted: by its constant name (textual) or its declaration
 * index (ordinal). Mirrors {@code jakarta.persistence.EnumType}, kept local so the dialect SPI
 * stays free of the JPA API dependency.
 */
public enum EnumStorage {
    STRING,
    ORDINAL
}

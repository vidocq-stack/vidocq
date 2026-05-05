package io.vidocq.mansart.data.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Selects which {@code DataSource} bean a Mansart repository should use when several are
 * available in the CDI container. Read by the {@code mansart-data-cdi} BCE at deployment time.
 *
 * <p>This is the only Mansart-proprietary annotation in the API surface — every other
 * mapping concept (Entity, Id, Column, Table, Version, ManyToOne, JoinColumn, …) is the
 * standard {@code jakarta.persistence.*} annotation, not a Mansart mirror.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD})
public @interface MansartDataSource {
    String value();
}

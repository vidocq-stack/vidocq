package io.vidocq.mansart.data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface JoinColumn {
    String name() default "";
    String referencedColumnName() default "";
    boolean nullable() default true;
    boolean unique() default false;
}

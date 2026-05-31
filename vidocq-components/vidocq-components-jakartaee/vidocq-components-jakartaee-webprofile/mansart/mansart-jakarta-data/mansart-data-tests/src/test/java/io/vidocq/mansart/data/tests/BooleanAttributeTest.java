package io.vidocq.mansart.data.tests;

import io.vidocq.mansart.data.core.RuntimeEntityModelBuilder;
import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.BooleanAttribute;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MANSART-001 regression: a {@code boolean}/{@link Boolean} entity field must be typed as a
 * {@link BooleanAttribute} on both the compile-time (APT {@code _BooleanFlag}) and the runtime
 * ({@link RuntimeEntityModelBuilder}) paths. Before the fix, both produced a
 * {@code NumericAttribute<Boolean>} that failed to compile.
 */
class BooleanAttributeTest {

    @Test
    void aptMetamodelTypesBooleanFields() {
        assertThat(_BooleanFlag.active).isInstanceOf(BooleanAttribute.class);
        assertThat(_BooleanFlag.active.columnName()).isEqualTo("active");
        assertThat(_BooleanFlag.active.javaType()).isEqualTo(Boolean.class);

        assertThat(_BooleanFlag.archived).isInstanceOf(BooleanAttribute.class);
        assertThat(_BooleanFlag.archived.javaType()).isEqualTo(Boolean.class);
    }

    @Test
    void aptMethodHandlesReadWriteBoolean() throws Throwable {
        BooleanFlag f = new BooleanFlag();
        _BooleanFlag.active.setter().invoke(f, true);
        assertThat((boolean) _BooleanFlag.active.getter().invoke(f)).isTrue();
    }

    @Test
    void runtimeBuilderTypesBooleanFields() {
        EntityModel<BooleanFlag> model = RuntimeEntityModelBuilder.build(BooleanFlag.class);
        Attribute<BooleanFlag, ?> active = model.attributes().stream()
                .filter(a -> a.name().equals("active")).findFirst().orElseThrow();
        assertThat(active).isInstanceOf(BooleanAttribute.class);
        assertThat(active.javaType()).isEqualTo(Boolean.class);
    }
}

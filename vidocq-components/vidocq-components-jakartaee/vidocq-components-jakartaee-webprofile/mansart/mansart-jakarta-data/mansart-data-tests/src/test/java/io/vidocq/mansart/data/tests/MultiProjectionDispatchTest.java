/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.data.tests;

import io.vidocq.mansart.data.core.JdqlExecutor;
import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.attribute.TextAttribute;
import io.vidocq.mansart.data.tests.AuthorRepository.AuthorView;
import jakarta.data.exceptions.EmptyResultException;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a multi-column projection's rows ({@code Object[]} each) are handed back for each return type a repository
 * method may declare: the rows themselves, or one record per row built from the projected columns in order.
 */
class MultiProjectionDispatchTest {

    private static final List<Attribute<?, ?>> TWO_COLUMNS = List.of(column("id"), column("name"));
    private static final Object[] ADA = {1L, "Ada"};
    private static final Object[] GRACE = {2L, "Grace"};
    private static final AuthorView ADA_VIEW = new AuthorView(1L, "Ada");
    private static final AuthorView GRACE_VIEW = new AuthorView(2L, "Grace");
    private static final List<Object[]> ROWS = List.of(ADA, GRACE);

    private static Attribute<?, ?> column(String name) {
        return new TextAttribute<>(name, name, Object.class, true, false, 255, null, null);
    }

    private static Object dispatch(Class<?> returnType, Class<?> element, List<Object[]> rows) {
        return JdqlExecutor.dispatchMultiProjection(returnType, element, rows, TWO_COLUMNS);
    }

    @Test
    void listCollectionAndIterableReturnTheRows() {
        assertThat(dispatch(List.class, Object[].class, ROWS)).isSameAs(ROWS);
        assertThat(dispatch(Collection.class, null, ROWS)).isSameAs(ROWS);
        assertThat(dispatch(Iterable.class, Object[].class, ROWS)).isSameAs(ROWS);
    }

    @Test
    void listOfRecordsMapsEachRow() {
        assertThat(dispatch(List.class, AuthorView.class, ROWS))
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(ADA_VIEW, GRACE_VIEW);
    }

    @Test
    void streamOfRowsOrOfRecords() {
        assertThat(((Stream<?>) dispatch(Stream.class, Object[].class, ROWS)).toList())
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(ADA, GRACE);
        assertThat(((Stream<?>) dispatch(Stream.class, AuthorView.class, ROWS)).toList())
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(ADA_VIEW, GRACE_VIEW);
    }

    @Test
    void twoDimensionalArrayHoldsTheRows() {
        Object[][] array = (Object[][]) dispatch(Object[][].class, Object[].class, ROWS);
        assertThat(array).hasDimensions(2, 2);
        assertThat(array[0]).isSameAs(ADA);
        assertThat(array[1]).isSameAs(GRACE);
    }

    @Test
    void recordArrayMapsEachRow() {
        AuthorView[] array = (AuthorView[]) dispatch(AuthorView[].class, AuthorView.class, ROWS);
        assertThat(array).containsExactly(ADA_VIEW, GRACE_VIEW);
    }

    @Test
    void otherArrayTypesAreRefused() {
        assertThatThrownBy(() -> dispatch(String[].class, String.class, ROWS))
                .isInstanceOf(MansartDataException.class)
                .hasMessageContaining("Object[][] or RecordType[]");
    }

    @Test
    void optionalHoldsTheFirstRowOrRecord() {
        assertThat(dispatch(Optional.class, Object[].class, ROWS)).isEqualTo(Optional.of(ADA));
        assertThat(dispatch(Optional.class, AuthorView.class, ROWS)).isEqualTo(Optional.of(ADA_VIEW));
        assertThat(dispatch(Optional.class, AuthorView.class, List.of())).isEqualTo(Optional.empty());
    }

    @Test
    void singleRecordIsTheFirstRow() {
        assertThat(dispatch(AuthorView.class, AuthorView.class, ROWS)).isEqualTo(ADA_VIEW);
    }

    @Test
    void singleRecordWithoutRowsIsAnEmptyResult() {
        List<Object[]> none = List.of();
        assertThatThrownBy(() -> dispatch(AuthorView.class, AuthorView.class, none))
                .isInstanceOf(EmptyResultException.class)
                .hasMessageContaining("AuthorView");
    }

    @Test
    void recordWithAnotherComponentCountIsRefused() {
        List<Attribute<?, ?>> oneColumn = List.of(column("id"));
        assertThatThrownBy(() -> JdqlExecutor.dispatchMultiProjection(List.class, AuthorView.class, ROWS, oneColumn))
                .isInstanceOf(MansartDataException.class)
                .hasMessageContaining("has 2 components but projection selected 1 attributes");
    }

    @Test
    void otherReturnTypesAreRefused() {
        assertThatThrownBy(() -> dispatch(String.class, Object[].class, ROWS))
                .isInstanceOf(MansartDataException.class)
                .hasMessageContaining("Unsupported multi-projection return type");
    }
}

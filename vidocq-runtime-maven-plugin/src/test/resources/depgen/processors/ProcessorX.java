/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package fixture.proc;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.TypeElement;
import java.util.Set;

/** Test fixture: throws an {@code Error}, not an exception, as soon as it sees {@code fixture.Marked}. */
@SupportedAnnotationTypes("fixture.Marked")
public class ProcessorX extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (!annotations.isEmpty()) {
            throw new StackOverflowError("processor recursion");
        }
        return false;
    }
}

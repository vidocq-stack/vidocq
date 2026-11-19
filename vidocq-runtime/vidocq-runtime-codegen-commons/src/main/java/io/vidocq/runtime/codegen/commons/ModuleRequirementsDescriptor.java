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
package io.vidocq.runtime.codegen.commons;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Parses an extension's {@value #RESOURCE} descriptor into a set of {@link Requirement}s. Each Vidocq
 * extension ships this resource to declare the JPMS directives its <em>consumer</em> module must hold
 * at runtime — the {@code requires} / {@code opens} that are dependency-driven (needed merely by
 * depending on the extension), with no annotation to trigger an APT check.
 *
 * <p>Format (a {@link Properties} file):</p>
 * <pre>
 *   requires            = java.sql, io.vidocq.runtime.extensions.essentials.migration
 *   opens               = db.migration
 *   opens.to.&lt;module&gt;    = pkg.a, pkg.b          # a qualified `opens ... to &lt;module&gt;` (rare)
 *   reason.&lt;name&gt;        = why it is needed         # name = a required module or opened package
 * </pre>
 */
public final class ModuleRequirementsDescriptor {

    /** Classpath/JAR location each extension ships its descriptor at. */
    public static final String RESOURCE = "META-INF/vidocq/module-requirements.properties";

    private static final String QUALIFIED_OPENS_PREFIX = "opens.to.";

    private ModuleRequirementsDescriptor() {
    }

    /** Parses the descriptor properties into requirements (empty when {@code props} is null/empty). */
    public static Set<Requirement> parse(Properties props) {
        Set<Requirement> out = new LinkedHashSet<>();
        if (props == null) {
            return out;
        }
        for (String module : csv(props.getProperty("requires"))) {
            out.add(Requirement.requires(module, reasonFor(props, module)));
        }
        for (String pkg : csv(props.getProperty("opens"))) {
            out.add(Requirement.opens(pkg, reasonFor(props, pkg)));
        }
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(QUALIFIED_OPENS_PREFIX) && key.length() > QUALIFIED_OPENS_PREFIX.length()) {
                String targetModule = key.substring(QUALIFIED_OPENS_PREFIX.length());
                for (String pkg : csv(props.getProperty(key))) {
                    out.add(Requirement.opensTo(pkg, Set.of(targetModule), reasonFor(props, pkg)));
                }
            }
        }
        return out;
    }

    private static String reasonFor(Properties props, String name) {
        return props.getProperty("reason." + name, "");
    }

    private static List<String> csv(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}

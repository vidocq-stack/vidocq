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
/**
 * Moteur de validation JWT pur (sans CDI ni JAX-RS).
 *
 * <p>Note Java Modules — workaround testCompile: this {@code module-info.java} is in
 * {@code src/main/module-info/} (not {@code src/main/java/}) so Maven Compiler Plugin
 * does not detect Java Modules during {@code testCompile} (test dependencies — champollion-jsonp, junit —
 * tournent sur le classpath, {@code useModulePath=false}). {@code maven-clean-plugin} retire
 * {@code module-info.class} before {@code testCompile}, then a {@code prepare-package} run
 * Recompile {@code module-info.java} alone. Java Modules wiring is validated by TCK (M6).</p>
 *
 * <p>{@code io.vidocq.cervantes.internal} will be exported qualifiedly to
 * {@code io.vidocq.cervantes.cdi.vauban} and {@code io.vidocq.cervantes.jaxrs} from M3/M4.ZZPH2ZZ
 */
module io.vidocq.cervantes.core {
    requires transitive io.vidocq.cervantes.api;
    requires io.vidocq.champollion.api; // jakarta.json (transitif)
    requires java.net.http;             // fetch JWKS distant (M2)

    //Internal implementation consumed by CDI (M3) and JAX-RS (M4).
    exports io.vidocq.cervantes.internal to io.vidocq.cervantes.cdi.vauban;
}

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
 * Descripteur de module explicite pour la spec MicroProfile JWT 2.1.
 *
 * <p>L'artefact officiel {@code org.eclipse.microprofile.jwt:microprofile-jwt-auth-api:2.1}
 * publié par la fondation Eclipse ne fournit NI {@code module-info.class} NI
 * {@code Automatic-Module-Name} ; jlink refuse ce type de module pour la composition d'un
 * runtime image. Ce module-info l'érige en module explicite, sans modifier le code de la spec.
 *
 * <p>Nom de module choisi : {@code org.eclipse.microprofile.jwt} (aligné sur le package
 * principal). Comme heisenberg-mp-ft-api, ce descripteur ne déclare que des {@code exports}
 * et aucun {@code requires} : la compilation de ce seul {@code module-info} (via
 * {@code --patch-module}) ne valide que l'existence des packages exportés (fournis par les
 * {@code .class} dépaquetés). Les dépendances réelles de la spec ({@code jakarta.json},
 * {@code jakarta.cdi}) sont déclarées par les modules consommateurs (cervantes-api,
 * cervantes-core, cervantes-cdi-vauban), qui en ont besoin à la compilation de leur propre code.
 */
module org.eclipse.microprofile.jwt {
    // The repackaged spec classes reference types from these modules in their OWN bytecode, so this
    // module must READ them at runtime (readability is per-module of the referencing class — consumers
    // reading these modules is not enough). `static`: mandatory only when compiling THIS module-info
    // (resolved via the three provided deps in the pom), NOT forced onto consumers — cervantes-api/core
    // use no CDI and must not have to put jakarta.cdi/inject on their path. At runtime the read edge
    // activates whenever the target is present, which it always is in a real MP-JWT deployment (the
    // Vidocq runtime / Arago resolve jakarta.json/cdi/inject via champollion + vauban). Invisible on the
    // class-path (TCK); only the module path enforces it — see cervantes BUG.md CERV-003.
    //   - jakarta.json   : Claims / JsonWebToken expose jakarta.json types.
    //   - jakarta.cdi    : @Claim is @Nonbinding; ClaimLiteral extends AnnotationLiteral.
    //   - jakarta.inject : @Claim is a @Qualifier.
    requires static jakarta.json;
    requires static jakarta.cdi;
    requires static jakarta.inject;

    exports org.eclipse.microprofile.auth;
    exports org.eclipse.microprofile.jwt;
    exports org.eclipse.microprofile.jwt.config;
}

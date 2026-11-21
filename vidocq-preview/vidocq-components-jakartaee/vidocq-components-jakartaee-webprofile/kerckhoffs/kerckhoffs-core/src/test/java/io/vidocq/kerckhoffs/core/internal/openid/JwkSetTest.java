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
package io.vidocq.kerckhoffs.core.internal.openid;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The reading of a JSON Web Key Set: only the RSA signature keys that can be read are kept. */
class JwkSetTest {

    private static final String N = "sXchDaQebHnPiGvyDOAT4saGEUetSyo9MKLOoWFsueri23bOdgWp4Dy1Wl"
            + "UzewbgBHod5pcM9H95GQRV3JDXboIRROSBigeC5yjU1hGzHHyXss8UDprecbAYxknTcQkhslANGRUZmdTOQ5qTRsLAt6BTYuyvVRdhS8exSZEy_c4gs_7svlJJQ4H9_NxsiIoLwAEk7-Q3UXERGYw_75IDrGA84-lA_-Ct4eTlXHBIY2EaV7t7LjJaynVJCpkv4LKjTTAumiGUIuQhrNhZLuF_CRRMs3zfCNUVJ6Ggq2D4kjCEQj4kQ2DHrS8Zaqs0sjvlQ1_pYcAe-iIiWS-B9eOsVQ";

    @Test
    void aSignatureKeyWithAnIdentifierIsKept() {
        JwkSet set = JwkSet.parse("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"a\",\"use\":\"sig\",\"n\":\"" + N + "\",\"e\":\"AQAB\"}]}");
        assertNotNull(set.find("a"));
        assertNull(set.find("b"));
        assertNull(set.find(null));
    }

    @Test
    void aKeyForEncryptionIsLeftOut() {
        assertNull(JwkSet.parse("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"a\",\"use\":\"enc\",\"n\":\"" + N + "\",\"e\":\"AQAB\"}]}")
                .find("a"));
    }

    @Test
    void aKeyOfAnotherKindIsLeftOut() {
        assertNull(JwkSet.parse("{\"keys\":[{\"kty\":\"oct\",\"kid\":\"a\",\"k\":\"c2VjcmV0\"}]}").find("a"));
    }

    @Test
    void aKeyWithoutIdentifierOrWithAnUnreadableModulusIsLeftOut() {
        JwkSet set = JwkSet.parse("{\"keys\":[{\"kty\":\"RSA\",\"n\":\"" + N + "\",\"e\":\"AQAB\"},"
                + "{\"kty\":\"RSA\",\"kid\":\"bad\",\"n\":\"%%%\",\"e\":\"AQAB\"}]}");
        assertNull(set.find("bad"));
    }

    @Test
    void aDocumentThatIsNotAKeySetIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> JwkSet.parse("{\"other\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> JwkSet.parse("not json"));
    }
}

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
package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWE protected header rules (RFC 7516 §4.1, MicroProfile JWT 2.2 §9.2.4): the five-part shape, the
 * {@code alg}/{@code enc}/{@code cty} members, {@code mp.jwt.decrypt.key.algorithm}, and the
 * decryption key formats accepted by {@link Jwe}.
 */
class JweHeaderRulesTest {

    private static final String NESTED = "aGVhZGVy.cGF5bG9hZA.c2ln";
    private static final String OAEP_256 = "RSA-OAEP-256";
    private static final String OAEP = "RSA-OAEP";
    private static final String GCM = "A256GCM";

    private static KeyPair recipient;

    @TempDir
    Path dir;

    @BeforeAll
    static void keys() throws Exception {
        recipient = TestJwts.rsaKeyPair();
    }

    private static String header(String alg, String enc, String cty) {
        StringBuilder json = new StringBuilder("{");
        if (alg != null) json.append("\"alg\":\"").append(alg).append("\",");
        json.append("\"enc\":\"").append(enc).append('"');
        if (cty != null) json.append(",\"cty\":\"").append(cty).append('"');
        return json.append('}').toString();
    }

    private static String encrypt(String header, boolean sha256) throws Exception {
        return TestJwe.encrypt(NESTED, recipient.getPublic(), header, sha256);
    }

    private static String rejection(JweDecryptor decryptor, String jwe) {
        return assertThrows(JwtValidationException.class, () -> decryptor.decryptToCompactJws(jwe)).getMessage();
    }

    private static String pkcs8Pem() {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(recipient.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static String privateJwk() {
        RSAPrivateCrtKey k = (RSAPrivateCrtKey) recipient.getPrivate();
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return "{\"kty\":\"RSA\",\"n\":\"" + b64.encodeToString(k.getModulus().toByteArray())
                + "\",\"e\":\"" + b64.encodeToString(k.getPublicExponent().toByteArray())
                + "\",\"d\":\"" + b64.encodeToString(k.getPrivateExponent().toByteArray()) + "\"}";
    }

    @Test
    void rsaOaepWithSha1_isDecrypted() throws Exception {
        JweDecryptor decryptor = new JweDecryptor(recipient.getPrivate());
        assertEquals(NESTED, decryptor.decryptToCompactJws(encrypt(header(OAEP, GCM, "JWT"), false)));
    }

    @Test
    void ctyIsCaseInsensitive_butMustBeJwt() throws Exception {
        JweDecryptor decryptor = new JweDecryptor(recipient.getPrivate());
        assertEquals(NESTED, decryptor.decryptToCompactJws(encrypt(header(OAEP_256, GCM, "jwt"), true)));
        assertTrue(rejection(decryptor, encrypt(header(OAEP_256, GCM, "JSON"), true)).contains("cty"));
        assertTrue(rejection(decryptor, encrypt(header(OAEP_256, GCM, null), true)).contains("cty"));
    }

    @Test
    void configuredAlgorithm_isEnforced() throws Exception {
        JweDecryptor onlySha256 = new JweDecryptor(recipient.getPrivate(), OAEP_256);
        assertEquals(NESTED, onlySha256.decryptToCompactJws(encrypt(header(OAEP_256, GCM, "JWT"), true)));
        assertTrue(rejection(onlySha256, encrypt(header(OAEP, GCM, "JWT"), false)).contains("mismatch"));
    }

    @Test
    void unsupportedOrMissingKeyManagementAlgorithm_isRejected() throws Exception {
        JweDecryptor decryptor = new JweDecryptor(recipient.getPrivate());
        assertTrue(rejection(decryptor, encrypt(header("RSA1_5", GCM, "JWT"), true)).contains("RSA1_5"));
        assertTrue(rejection(decryptor, encrypt(header(null, GCM, "JWT"), true)).contains("key-management"));
    }

    @Test
    void unsupportedContentEncryption_isRejected() throws Exception {
        JweDecryptor decryptor = new JweDecryptor(recipient.getPrivate());
        assertTrue(rejection(decryptor, encrypt(header(OAEP_256, "A128GCM", "JWT"), true)).contains("A128GCM"));
    }

    @Test
    void wrongShapeOrUnreadableHeader_isRejected() {
        JweDecryptor decryptor = new JweDecryptor(recipient.getPrivate());
        assertTrue(rejection(decryptor, "a.b.c").contains("5 parts"));
        assertTrue(rejection(decryptor, "bm90LWpzb24.b.c.d.e").contains("protected header"));
    }

    @Test
    void inlineDecryptionKey_acceptsPkcs8PemAndJwk() throws Exception {
        String jwe = encrypt(header(OAEP_256, GCM, "JWT"), true);
        assertEquals(NESTED, Jwe.decryptorFromInlinePem(pkcs8Pem(), null).decryptToCompactJws(jwe));
        assertEquals(NESTED, Jwe.decryptorFromInlinePem(privateJwk(), OAEP_256).decryptToCompactJws(jwe));
    }

    @Test
    void decryptionKeyLocation_isRead() throws Exception {
        Path pem = Files.writeString(dir.resolve("private.pem"), pkcs8Pem(), StandardCharsets.US_ASCII);
        String jwe = encrypt(header(OAEP_256, GCM, "JWT"), true);
        assertEquals(NESTED, Jwe.decryptorFromLocation(pem.toUri().toString(), null).decryptToCompactJws(jwe));
    }

    @Test
    void invalidInlineDecryptionKey_isRejected() {
        assertThrows(JwtValidationException.class, () -> Jwe.decryptorFromInlinePem("not a key", null));
    }
}

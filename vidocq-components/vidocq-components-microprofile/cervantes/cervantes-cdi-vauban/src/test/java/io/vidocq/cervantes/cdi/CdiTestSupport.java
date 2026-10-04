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
package io.vidocq.cervantes.cdi;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonWriter;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/** Shared test utilities: key generation, RS256 JWT creation, and in-memory MicroProfile Config. */
public final class CdiTestSupport {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private CdiTestSupport() {}

    public static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    /** X.509 base64-encoded public key — expected value of {@code mp.jwt.verify.publickey}. */
    public static String publicKeyBase64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** A freshly generated EC (secp256r1) public key as a PEM {@code SubjectPublicKeyInfo}. */
    public static String ecPublicKeyPem() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(g.generateKeyPair().getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    public static String signRs256(JsonObject claims, PrivateKey key) throws Exception {
        JsonObject header = Json.createObjectBuilder().add("alg", "RS256").add("typ", "JWT").build();
        String h = B64URL.encodeToString(toBytes(header));
        String p = B64URL.encodeToString(toBytes(claims));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update((h + '.' + p).getBytes(StandardCharsets.US_ASCII));
        return h + '.' + p + '.' + B64URL.encodeToString(signer.sign());
    }

    public static Config config(Map<String, String> values) {
        return ConfigProviderResolver.instance().getBuilder()
                .withSources(new MapSource(values))
                .build();
    }

    /** Registers a global Config (resolved by {@code ConfigProvider.getConfig()} in the container). */
    public static void registerGlobalConfig(Map<String, String> values) {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config custom = resolver.getBuilder()
                .withSources(new MapSource(values))
                .forClassLoader(Thread.currentThread().getContextClassLoader())
                .build();
        resolver.registerConfig(custom, Thread.currentThread().getContextClassLoader());
    }

    public static void releaseGlobalConfig() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        resolver.releaseConfig(resolver.getConfig(Thread.currentThread().getContextClassLoader()));
    }

    private static byte[] toBytes(JsonObject o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonWriter w = Json.createWriter(out)) {
            w.writeObject(o);
        }
        return out.toByteArray();
    }

    private record MapSource(Map<String, String> values) implements ConfigSource {
        @Override public Map<String, String> getProperties() { return values; }
        @Override public Set<String> getPropertyNames() { return values.keySet(); }
        @Override public String getValue(String name) { return values.get(name); }
        @Override public String getName() { return "cervantes-cdi-test"; }
        @Override public int getOrdinal() { return 1000; }
    }
}

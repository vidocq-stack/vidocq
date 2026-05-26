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

/** Outils de test partagés : génération de clés, forge de JWT RS256, et Config MicroProfile in-memory. */
public final class CdiTestSupport {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private CdiTestSupport() {}

    public static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    /** Clé publique encodée X.509 en base64 — valeur attendue de {@code mp.jwt.verify.publickey}. */
    public static String publicKeyBase64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
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

    /** Enregistre une Config globale (résolue par {@code ConfigProvider.getConfig()} dans le container). */
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

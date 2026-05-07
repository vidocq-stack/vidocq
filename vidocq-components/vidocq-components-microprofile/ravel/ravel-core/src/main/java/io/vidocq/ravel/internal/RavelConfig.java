/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Implémentation MP Config 3.1 §2.1 — cascade des {@link ConfigSource} par ordinal
 * décroissant, premier match gagne. Tie-breaking sur l'ordre d'enregistrement.
 *
 * <p>Résolution des converters (§5) :</p>
 * <ol>
 *   <li>lookup direct dans la table {@code type → Converter<?>} (built-in + applicatifs)</li>
 *   <li>type tableau {@code T[]} → {@link ArrayConverter} construit dynamiquement
 *       à partir du converter pour {@code T}</li>
 *   <li>type {@code enum} ou classe avec {@code of/valueOf/parse/(String)} →
 *       {@link ImplicitConverter} (§5.2)</li>
 *   <li>sinon → {@link IllegalArgumentException}</li>
 * </ol>
 *
 * <p><b>Thread-safety</b> : immutable après construction ; cache des converters
 * dérivés (arrays, implicits) en {@link ConcurrentHashMap}. Pas de
 * {@code synchronized}, pas de {@code ThreadLocal} — virtual-thread-friendly.</p>
 */
public final class RavelConfig implements Config {

    private static final ScopedValue<Set<String>> EXPRESSION_STACK = ScopedValue.newInstance();

    private final List<ConfigSource> sources;
    private final Map<Class<?>, Converter<?>> converters;
    private final ClassLoader classLoader;
    private final boolean expressionsEnabled;

    /** Cache des converters dérivés (arrays, implicits) — résolus à la première lookup. */
    private final ConcurrentMap<Class<?>, Converter<?>> derivedConverters = new ConcurrentHashMap<>();

    public RavelConfig(List<ConfigSource> sources,
                       Map<Class<?>, Converter<?>> converters,
                       ClassLoader classLoader) {
        this(sources, converters, classLoader, true);
    }

    public RavelConfig(List<ConfigSource> sources,
                       Map<Class<?>, Converter<?>> converters,
                       ClassLoader classLoader,
                       boolean expressionsEnabled) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(converters, "converters");
        // tri stable : ordinal décroissant ; en cas d'égalité, ordre d'enregistrement préservé.
        var sorted = new ArrayList<>(sources);
        sorted.sort(Comparator.comparingInt(ConfigSource::getOrdinal).reversed());
        this.sources = List.copyOf(sorted);
        this.converters = Map.copyOf(converters);
        this.classLoader = classLoader;
        this.expressionsEnabled = expressionsEnabled;
    }

    @Override
    public <T> T getValue(String propertyName, Class<T> propertyType) {
        return getOptionalValue(propertyName, propertyType)
                .orElseThrow(() -> new NoSuchElementException(
                        "Property '" + propertyName + "' not found"));
    }

    @Override
    public ConfigValue getConfigValue(String propertyName) {
        Objects.requireNonNull(propertyName, "propertyName");
        RawLookup raw = lookupRawWithSource(propertyName);
        if (raw != null) {
            String resolved = resolveRawValue(propertyName, raw.value());
            return new RavelConfigValue(
                    propertyName, resolved, raw.value(), raw.sourceName(), raw.sourceOrdinal());
        }
        return RavelConfigValue.absent(propertyName);
    }

    @Override
    public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
        Objects.requireNonNull(propertyName, "propertyName");
        Objects.requireNonNull(propertyType, "propertyType");
        String raw = lookupRaw(propertyName);
        if (raw == null || raw.isEmpty()) {
            // §2.1.4 — empty string is considered as null.
            return Optional.empty();
        }
        Converter<T> converter = findConverter(propertyType);
        return Optional.ofNullable(converter.convert(raw));
    }

    @Override
    public Iterable<String> getPropertyNames() {
        // Union ordonnée par cascade (ordinal décroissant), pas de duplicate.
        var names = new LinkedHashSet<String>();
        for (ConfigSource source : sources) {
            names.addAll(source.getPropertyNames());
        }
        return Set.copyOf(names);
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return sources;  // déjà immutable via List.copyOf
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
        Objects.requireNonNull(forType, "forType");
        Converter<?> direct = converters.get(forType);
        if (direct != null) return Optional.of((Converter<T>) direct);
        Converter<?> derived = derivedConverters.get(forType);
        if (derived != null) return Optional.of((Converter<T>) derived);
        // Tente une résolution dynamique sans la stocker en cache : lookup pur.
        Converter<T> resolved = resolveConverter(forType);
        return Optional.ofNullable(resolved);
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new IllegalArgumentException(
                "Cannot unwrap " + RavelConfig.class.getName() + " to " + type.getName());
    }

    /** ClassLoader associé — utilisé par le ProviderResolver pour le registre per-CL. */
    ClassLoader getClassLoader() {
        return classLoader;
    }

    // ---------- internals ----------

    private String lookupRaw(String propertyName) {
        RawLookup raw = lookupRawWithSource(propertyName);
        if (raw == null) {
            return null;
        }
        return resolveRawValue(propertyName, raw.value());
    }

    private RawLookup lookupRawWithSource(String propertyName) {
        for (ConfigSource source : sources) {
            String value = source.getValue(propertyName);
            if (value != null) {
                return new RawLookup(value, source.getName(), source.getOrdinal());
            }
        }
        return null;
    }

    private String resolveRawValue(String propertyName, String raw) {
        if (!expressionsEnabled || raw == null || raw.indexOf('$') < 0) {
            return raw;
        }
        Set<String> current = EXPRESSION_STACK.isBound() ? EXPRESSION_STACK.get() : Set.of();
        if (current.contains(propertyName)) {
            throw new IllegalArgumentException("Circular property expression detected: " + current + " -> " + propertyName);
        }
        var next = new HashSet<>(current);
        next.add(propertyName);
        final String[] holder = new String[1];
        ScopedValue.where(EXPRESSION_STACK, Set.copyOf(next)).run(() -> holder[0] = resolveTemplate(raw));
        return holder[0];
    }

    private String resolveTemplate(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '$') {
                out.append('$');
                i++;
                continue;
            }
            if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                int end = findExpressionEnd(text, i + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("Unterminated property expression in: " + text);
                }
                String exprBody = text.substring(i + 2, end);
                out.append(resolveExpression(exprBody));
                i = end;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int findExpressionEnd(String text, int from) {
        int depth = 1;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                depth++;
                i++;
                continue;
            }
            if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String resolveExpression(String expression) {
        int split = findTopLevelDefaultSeparator(expression);
        String keyExpr = split < 0 ? expression : expression.substring(0, split);
        String defaultExpr = split < 0 ? null : expression.substring(split + 1);

        String key = resolveTemplate(keyExpr);
        if (!key.isEmpty()) {
            RawLookup referenced = lookupRawWithSource(key);
            if (referenced != null) {
                return resolveRawValue(key, referenced.value());
            }
        }
        if (defaultExpr != null) {
            return resolveTemplate(defaultExpr);
        }
        throw new IllegalArgumentException("No config value found for expression ${" + expression + "}");
    }

    private static int findTopLevelDefaultSeparator(String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '$' && i + 1 < expression.length() && expression.charAt(i + 1) == '{') {
                depth++;
                i++;
                continue;
            }
            if (c == '}') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (c == ':' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private record RawLookup(String value, String sourceName, int sourceOrdinal) {}

    @SuppressWarnings("unchecked")
    private <T> Converter<T> findConverter(Class<T> type) {
        Converter<?> conv = converters.get(type);
        if (conv != null) return (Converter<T>) conv;
        Converter<?> cached = derivedConverters.get(type);
        if (cached != null) return (Converter<T>) cached;
        Converter<T> resolved = resolveConverter(type);
        if (resolved == null) {
            throw new IllegalArgumentException(
                    "No Converter found for " + type.getName()
                            + " — register one via ConfigBuilder.withConverter(...) or"
                            + " expose a public static of/valueOf/parse method or a"
                            + " (String) constructor (MP Config 3.1 §5.2).");
        }
        derivedConverters.put(type, resolved);
        return resolved;
    }

    /**
     * Résout dynamiquement un converter pour un type non pré-enregistré :
     * tableaux (§5.4) → {@link ArrayConverter}, enums + types automatiques (§5.2)
     * → {@link ImplicitConverter}.
     *
     * @return le converter résolu, ou {@code null} si aucune stratégie ne s'applique.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> Converter<T> resolveConverter(Class<T> type) {
        if (type.isArray()) {
            Class<?> component = type.getComponentType();
            Converter<?> elem;
            try {
                elem = findConverter(component);
            } catch (IllegalArgumentException e) {
                return null;
            }
            return (Converter<T>) new ArrayConverter(component, elem);
        }
        return ImplicitConverter.create(type);
    }
}

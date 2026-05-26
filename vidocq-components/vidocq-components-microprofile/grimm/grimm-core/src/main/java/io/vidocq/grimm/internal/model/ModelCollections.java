package io.vidocq.grimm.internal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ModelCollections {

    private ModelCollections() {
    }

    static <K, V> Map<K, V> mutableMap(Map<K, V> source) {
        return source == null ? null : new LinkedHashMap<>(source);
    }

    static <T> List<T> mutableList(List<T> source) {
        return source == null ? null : new ArrayList<>(source);
    }

    static <K, V> Map<K, V> ensureMutableMap(Map<K, V> source) {
        if (source == null) {
            return new LinkedHashMap<>();
        }
        if (source instanceof LinkedHashMap<?, ?>) {
            return source;
        }
        return new LinkedHashMap<>(source);
    }

    static <K, V> Map<K, V> copyOnWriteMap(Map<K, V> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    static <T> List<T> ensureMutableList(List<T> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        if (source instanceof ArrayList<?>) {
            return source;
        }
        return new ArrayList<>(source);
    }

    static <T> List<T> copyOnWriteList(List<T> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    static <K, V> Map<K, V> immutableMapView(Map<K, V> source) {
        return source == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    static <T> List<T> immutableListView(List<T> source) {
        return source == null ? null : Collections.unmodifiableList(new ArrayList<>(source));
    }
}


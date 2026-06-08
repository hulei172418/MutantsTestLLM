package org.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapUtils {

    // Supports arbitrary number of key-value pairs (alternating key and value)
    public static <K, V> Map<K, V> of(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Invalid number of arguments. Expected pairs of keys and values.");
        }

        Map<K, V> map = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            @SuppressWarnings("unchecked")
            K key = (K) entries[i];
            @SuppressWarnings("unchecked")
            V value = (V) entries[i + 1];
            map.put(key, value);
        }

        return Collections.unmodifiableMap(map);
    }

    public static void main(String[] args) {
        // Create List<Map<String, String>> defs_at_mut
        List<Map<String, String>> heap_access = new ArrayList<>();

        // Use MapUtils.of() to create a Map and add to defs_at_mut
        heap_access.add(MapUtils.of("var", "lhs.toString()", "unit", "mutUnit.toString()"));
        heap_access.add(MapUtils.of("var", "v.toString()", "unit", "u.toString()", "sink", "sinkKind(u)"));
        heap_access.add(
                MapUtils.of("base", "ar.getBase().toString()", "index", "ar.getIndex().toString()", "kind", "write"));

        // Print defs_at_mut
        System.out.println(heap_access);
    }
}

/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.fieldmapping.mapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Split-on-dot nested access for the transform. The same approach {@code ClusterElementContextImpl}'s nested
 * implementation takes; that one is reachable only from a cluster-element context, so the component carries its own.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
final class FieldMappingPaths {

    private FieldMappingPaths() {
    }

    static boolean containsPath(Map<String, ?> map, String path) {
        String[] segments = path.split("\\.");
        Map<String, ?> current = map;

        for (int index = 0; index < segments.length; index++) {
            if (!current.containsKey(segments[index])) {
                return false;
            }

            if (index == segments.length - 1) {
                return true;
            }

            if (!(current.get(segments[index]) instanceof Map<?, ?> nested)) {
                return false;
            }

            current = castMap(nested);
        }

        return false;
    }

    static Object getValue(Map<String, ?> map, String path) {
        String[] segments = path.split("\\.");
        Map<String, ?> current = map;

        for (int index = 0; index < segments.length - 1; index++) {
            if (!(current.get(segments[index]) instanceof Map<?, ?> nested)) {
                return null;
            }

            current = castMap(nested);
        }

        return current.get(segments[segments.length - 1]);
    }

    static void setValue(Map<String, Object> map, String path, Object value) {
        String[] segments = path.split("\\.");
        Map<String, Object> current = map;

        for (int index = 0; index < segments.length - 1; index++) {
            Object existing = current.get(segments[index]);

            if (existing instanceof Map<?, ?> nested) {
                current = castMutableMap(nested);
            } else {
                Map<String, Object> created = new LinkedHashMap<>();

                current.put(segments[index], created);

                current = created;
            }
        }

        current.put(segments[segments.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> castMap(Map<?, ?> map) {
        return (Map<String, ?>) map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMutableMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}

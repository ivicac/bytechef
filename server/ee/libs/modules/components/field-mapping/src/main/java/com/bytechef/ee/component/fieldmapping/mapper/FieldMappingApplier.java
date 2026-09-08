/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.fieldmapping.mapper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a {@link FieldMappingDescriptor} to a payload. Unmapped source keys are dropped unless
 * {@code includeUnmapped}; a missing source key omits its destination key rather than writing {@code null}; a list maps
 * element-wise; when several mappings share a destination the last declared one wins.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class FieldMappingApplier {

    private FieldMappingApplier() {
    }

    public static Object apply(
        FieldMappingDescriptor descriptor, Object data, FieldMappingDirection direction, boolean includeUnmapped) {

        if (data instanceof Map<?, ?> map) {
            return applyToObject(descriptor, castMap(map), direction, includeUnmapped);
        }

        if (data instanceof List<?> list) {
            return list.stream()
                .map(item -> apply(descriptor, item, direction, includeUnmapped))
                .toList();
        }

        throw new IllegalArgumentException(
            "Field mapping data must be an object or an array of objects, got: " +
                (data == null ? "null" : data.getClass()
                    .getSimpleName()));
    }

    private static Map<String, Object> applyToObject(
        FieldMappingDescriptor descriptor, Map<String, ?> source, FieldMappingDirection direction,
        boolean includeUnmapped) {

        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> consumedTopLevelKeys = new HashSet<>();

        for (FieldMappingDescriptor.Mapping mapping : descriptor.mappings()) {
            String sourcePath = direction.sourcePath(mapping);

            consumedTopLevelKeys.add(sourcePath.split("\\.")[0]);

            if (FieldMappingPaths.containsPath(source, sourcePath)) {
                FieldMappingPaths.setValue(
                    result, direction.destinationPath(mapping), FieldMappingPaths.getValue(source, sourcePath));
            }
        }

        if (includeUnmapped) {
            for (Map.Entry<String, ?> entry : source.entrySet()) {
                if (!consumedTopLevelKeys.contains(entry.getKey())) {
                    result.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> castMap(Map<?, ?> map) {
        return (Map<String, ?>) map;
    }
}

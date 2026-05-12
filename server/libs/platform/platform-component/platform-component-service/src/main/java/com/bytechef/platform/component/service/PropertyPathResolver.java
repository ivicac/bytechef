/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.component.service;

import com.bytechef.platform.component.domain.ArrayProperty;
import com.bytechef.platform.component.domain.ObjectProperty;
import com.bytechef.platform.component.domain.Property;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Schema-only traversal of dotted property paths through {@link Property} hierarchies. Used by the lookup-options
 * precondition gate in the Action/Trigger definition services to resolve nested lookup-eligible properties without the
 * runtime {@code Parameters}/{@code Context} machinery used by {@code ComponentDefinitionRegistry#getProperty}.
 *
 * <p>
 * Path conventions:
 * <ul>
 * <li>{@code parent.child} — descend into an {@link ObjectProperty}'s children.</li>
 * <li>{@code arrayProp[].child} — explicit descent into the first item type of an {@link ArrayProperty}.</li>
 * <li>{@code parent.child} — implicit descent when {@code parent} is an array whose single item type is an
 * {@link ObjectProperty}.</li>
 * <li>{@code propName} — top-level match by {@link Property#getName()}.</li>
 * </ul>
 *
 * <p>
 * Unknown segments return {@code null} cleanly so the precondition gate can emit the safest fallback envelope rather
 * than throwing.
 *
 * @author Ivica Cardic
 */
final class PropertyPathResolver {

    private PropertyPathResolver() {
    }

    /**
     * Traverses {@code properties} along the dotted {@code path}, descending into {@link ObjectProperty} children and
     * {@link ArrayProperty} item types as needed. Returns {@code null} if {@code path} is null/blank or any segment
     * cannot be resolved.
     */
    static @Nullable Property findPropertyByPath(List<? extends Property> properties, @Nullable String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        String[] segments = path.split("\\.");

        List<? extends Property> currentProperties = properties;
        Property current = null;

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean arrayDescent = segment.endsWith("[]");
            String name = arrayDescent ? segment.substring(0, segment.length() - 2) : segment;

            current = null;

            for (Property property : currentProperties) {
                if (name.equals(property.getName())) {
                    current = property;

                    break;
                }
            }

            if (current == null) {
                return null;
            }

            if (i < segments.length - 1) {
                if (arrayDescent) {
                    // explicit `arrayProp[].child` — descend into the array's first item type; if it's an
                    // object, expose its children so the next segment can match a field name
                    if (!(current instanceof ArrayProperty arrayProperty)) {
                        return null;
                    }

                    List<? extends Property> items = arrayProperty.getItems();

                    if (items == null || items.isEmpty()) {
                        return null;
                    }

                    Property firstItem = items.get(0);

                    if (firstItem instanceof ObjectProperty objectItem) {
                        currentProperties = objectItem.getProperties();
                    } else {
                        // first item is itself a leaf — no further children to traverse
                        return null;
                    }
                } else {
                    currentProperties = childPropertiesOf(current);

                    if (currentProperties == null) {
                        return null;
                    }
                }
            } else if (arrayDescent) {
                // path ends in `[]` — caller wants the array's item type, not the array property itself
                if (!(current instanceof ArrayProperty arrayProperty)) {
                    return null;
                }

                List<? extends Property> items = arrayProperty.getItems();

                if (items == null || items.isEmpty()) {
                    return null;
                }

                current = items.get(0);
            }
        }

        return current;
    }

    private static @Nullable List<? extends Property> childPropertiesOf(Property property) {
        if (property instanceof ObjectProperty objectProperty) {
            return objectProperty.getProperties();
        }

        if (property instanceof ArrayProperty arrayProperty) {
            // implicit descent into array items — accept `parent.child` when parent is an array of objects
            List<? extends Property> items = arrayProperty.getItems();

            if (items != null && items.size() == 1 && items.get(0) instanceof ObjectProperty objectItem) {
                return objectItem.getProperties();
            }
        }

        return null;
    }
}

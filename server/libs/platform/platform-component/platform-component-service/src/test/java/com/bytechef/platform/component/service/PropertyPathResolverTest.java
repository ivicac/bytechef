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

import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.string;
import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.component.domain.Property;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the dotted-path traversal used by the AI Hub lookup-options precondition gate.
 *
 * @author Ivica Cardic
 */
public class PropertyPathResolverTest {

    @Test
    public void testReturnsTopLevelPropertyMatchByName() {
        List<? extends Property> properties = buildProperties();

        Property resolved = PropertyPathResolver.findPropertyByPath(properties, "topLevel");

        assertThat(resolved).isNotNull();
        assertThat(resolved.getName()).isEqualTo("topLevel");
    }

    @Test
    public void testResolvesDottedPathThroughObjectProperty() {
        List<? extends Property> properties = buildProperties();

        Property resolved = PropertyPathResolver.findPropertyByPath(properties, "parent.child");

        assertThat(resolved).isNotNull();
        assertThat(resolved.getName()).isEqualTo("child");
    }

    @Test
    public void testResolvesExplicitArrayDescentWithBracketSuffix() {
        List<? extends Property> properties = buildProperties();

        Property resolved = PropertyPathResolver.findPropertyByPath(properties, "items[].id");

        assertThat(resolved).isNotNull();
        assertThat(resolved.getName()).isEqualTo("id");
    }

    @Test
    public void testResolvesImplicitArrayDescentWhenArrayHasSingleObjectItem() {
        List<? extends Property> properties = buildProperties();

        Property resolved = PropertyPathResolver.findPropertyByPath(properties, "items.id");

        assertThat(resolved).isNotNull();
        assertThat(resolved.getName()).isEqualTo("id");
    }

    @Test
    public void testReturnsNullForUnknownIntermediateSegment() {
        List<? extends Property> properties = buildProperties();

        Property resolved = PropertyPathResolver.findPropertyByPath(properties, "parent.missing.child");

        assertThat(resolved).isNull();
    }

    @Test
    public void testReturnsNullForUnknownFinalSegment() {
        List<? extends Property> properties = buildProperties();

        Property resolved = PropertyPathResolver.findPropertyByPath(properties, "parent.missing");

        assertThat(resolved).isNull();
    }

    @Test
    public void testReturnsNullForUnknownTopLevelSegment() {
        List<? extends Property> properties = buildProperties();

        Property resolved = PropertyPathResolver.findPropertyByPath(properties, "nope");

        assertThat(resolved).isNull();
    }

    @Test
    public void testReturnsNullForNullPath() {
        List<? extends Property> properties = buildProperties();

        Property resolved = PropertyPathResolver.findPropertyByPath(properties, null);

        assertThat(resolved).isNull();
    }

    @Test
    public void testReturnsNullForBlankPath() {
        List<? extends Property> properties = buildProperties();

        Property resolved = PropertyPathResolver.findPropertyByPath(properties, "   ");

        assertThat(resolved).isNull();
    }

    @Test
    public void testReturnsNullWhenDescendingIntoLeafProperty() {
        List<? extends Property> properties = buildProperties();

        Property resolved = PropertyPathResolver.findPropertyByPath(properties, "topLevel.child");

        assertThat(resolved).isNull();
    }

    @Test
    public void testReturnsArrayItemTypeWhenPathEndsInBracketSuffix() {
        List<? extends Property> properties = buildProperties();

        Property resolved = PropertyPathResolver.findPropertyByPath(properties, "items[]");

        assertThat(resolved).isNotNull();
        // path ends in `[]` → caller wants the item type itself, which is an object with name `null` (anonymous item)
        assertThat(resolved).isInstanceOf(com.bytechef.platform.component.domain.ObjectProperty.class);
    }

    private static List<? extends Property> buildProperties() {
        // Build SDK-level definitions, then convert to domain Property instances via Property.toProperty.
        return List.of(
            Property.toProperty(string("topLevel")),
            Property.toProperty(
                object("parent")
                    .properties(
                        string("child"),
                        integer("count"))),
            Property.toProperty(
                array("items")
                    .items(
                        object()
                            .properties(
                                string("id"),
                                string("sheetId")))));
    }
}

/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.fieldmapping.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.test.definition.MockParametersFactory;
import com.bytechef.ee.component.fieldmapping.mapper.FieldMappingDescriptor;
import com.bytechef.ee.component.fieldmapping.mapper.FieldMappingDirection;
import com.bytechef.ee.component.fieldmapping.resolver.FieldMappingDescriptorResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class FieldMappingMapActionTest {

    private static final FieldMappingDescriptor DESCRIPTOR = new FieldMappingDescriptor(
        "contacts", List.of(new FieldMappingDescriptor.Mapping("title", "first_name")));

    private final FieldMappingDescriptorResolver resolver = mock(FieldMappingDescriptorResolver.class);
    private final ActionContext context = mock(ActionContext.class);

    @Test
    void testMapToIntegrationResolvesByObjectNameAndAppliesToAnObject() {
        when(resolver.resolve(eq("Contacts"), eq(context))).thenReturn(DESCRIPTOR);

        Parameters inputParameters = MockParametersFactory.create(
            Map.of("objectName", "Contacts", "inputType", "OBJECT", "data", Map.of("title", "Dr", "x", 1)));

        Object result = FieldMappingMapAction.perform(
            FieldMappingDirection.TO_INTEGRATION, resolver, inputParameters, context);

        assertEquals(Map.of("first_name", "Dr"), result);
    }

    @Test
    void testMapToApplicationAppliesToAnArrayAndHonoursIncludeUnmapped() {
        when(resolver.resolve(eq("Contacts"), eq(context))).thenReturn(DESCRIPTOR);

        Parameters inputParameters = MockParametersFactory.create(
            Map.of(
                "objectName", "Contacts", "inputType", "ARRAY", "includeUnmapped", true,
                "data", List.of(Map.of("first_name", "Dr", "stage", "lead"))));

        Object result = FieldMappingMapAction.perform(
            FieldMappingDirection.TO_APPLICATION, resolver, inputParameters, context);

        assertEquals(List.of(Map.of("title", "Dr", "stage", "lead")), result);
    }

    @Test
    void testDefinitionsCarryTheDirectionsNames() {
        assertEquals(
            "mapToIntegration",
            FieldMappingMapAction.of(FieldMappingDirection.TO_INTEGRATION, resolver)
                .getName());
        assertEquals(
            "mapToApplication",
            FieldMappingMapAction.of(FieldMappingDirection.TO_APPLICATION, resolver)
                .getName());
    }
}

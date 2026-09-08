/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.fieldmapping.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class FieldMappingApplierTest {

    private static final FieldMappingDescriptor DESCRIPTOR = new FieldMappingDescriptor(
        "contacts",
        List.of(
            new FieldMappingDescriptor.Mapping("title", "first_name"),
            new FieldMappingDescriptor.Mapping("priority", "hs_priority")));

    @Test
    void testDescriptorParsesTheSavedShape() {
        FieldMappingDescriptor descriptor = FieldMappingDescriptor.of(
            Map.of(
                "objectType", "contacts",
                "mappings", List.of(
                    Map.of(
                        "applicationField", Map.of("label", "Title", "value", "title", "custom", false),
                        "integrationField", "first_name"),
                    Map.of(
                        "applicationField", Map.of("label", "Priority", "value", "priority", "custom", true),
                        "integrationField", "hs_priority"))));

        assertEquals(DESCRIPTOR, descriptor);
    }

    @Test
    void testDescriptorRejectsEmptyMappings() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> FieldMappingDescriptor.of(Map.of("objectType", "contacts", "mappings", List.of())));

        assertEquals("Field mapping has no mappings", exception.getMessage());
    }

    @Test
    void testDescriptorRejectsMissingMappings() {
        assertThrows(
            IllegalArgumentException.class, () -> FieldMappingDescriptor.of(Map.of("objectType", "contacts")));
    }

    @Test
    void testMapToIntegrationRenamesApplicationKeysToIntegrationKeys() {
        Object result = FieldMappingApplier.apply(
            DESCRIPTOR, Map.of("title", "Dr", "priority", "high"), FieldMappingDirection.TO_INTEGRATION, false);

        assertEquals(Map.of("first_name", "Dr", "hs_priority", "high"), result);
    }

    @Test
    void testMapToApplicationRenamesIntegrationKeysToApplicationKeys() {
        Object result = FieldMappingApplier.apply(
            DESCRIPTOR, Map.of("first_name", "Dr", "hs_priority", "high"), FieldMappingDirection.TO_APPLICATION,
            false);

        assertEquals(Map.of("title", "Dr", "priority", "high"), result);
    }

    @Test
    void testUnmappedSourceKeysAreDroppedByDefault() {
        Object result = FieldMappingApplier.apply(
            DESCRIPTOR, Map.of("first_name", "Dr", "lifecyclestage", "lead"), FieldMappingDirection.TO_APPLICATION,
            false);

        assertEquals(Map.of("title", "Dr"), result);
    }

    @Test
    void testUnmappedSourceKeysPassThroughWhenIncludeUnmapped() {
        Object result = FieldMappingApplier.apply(
            DESCRIPTOR, Map.of("first_name", "Dr", "lifecyclestage", "lead"), FieldMappingDirection.TO_APPLICATION,
            true);

        assertEquals(Map.of("title", "Dr", "lifecyclestage", "lead"), result);
    }

    @Test
    void testMissingSourceKeyOmitsDestinationKeyRatherThanWritingNull() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldMappingApplier.apply(
            DESCRIPTOR, Map.of("title", "Dr"), FieldMappingDirection.TO_INTEGRATION, false);

        assertEquals(Map.of("first_name", "Dr"), result);
        assertFalse(result.containsKey("hs_priority"));
    }

    @Test
    void testPresentNullSourceValueIsCarriedAcross() {
        Map<String, Object> source = new HashMap<>();

        source.put("title", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldMappingApplier.apply(
            DESCRIPTOR, source, FieldMappingDirection.TO_INTEGRATION, false);

        assertTrue(result.containsKey("first_name"));
        assertNull(result.get("first_name"));
    }

    @Test
    void testDottedPathsResolveNestedStructuresOnBothSides() {
        FieldMappingDescriptor descriptor = new FieldMappingDescriptor(
            "contacts", List.of(new FieldMappingDescriptor.Mapping("contact.title", "properties.firstname")));

        Object toIntegration = FieldMappingApplier.apply(
            descriptor, Map.of("contact", Map.of("title", "Dr")), FieldMappingDirection.TO_INTEGRATION, false);

        assertEquals(Map.of("properties", Map.of("firstname", "Dr")), toIntegration);

        Object toApplication = FieldMappingApplier.apply(
            descriptor, Map.of("properties", Map.of("firstname", "Dr")), FieldMappingDirection.TO_APPLICATION,
            false);

        assertEquals(Map.of("contact", Map.of("title", "Dr")), toApplication);
    }

    @Test
    void testListPayloadMapsElementWise() {
        Object result = FieldMappingApplier.apply(
            DESCRIPTOR,
            List.of(Map.of("title", "Dr"), Map.of("title", "Prof", "priority", "low")),
            FieldMappingDirection.TO_INTEGRATION, false);

        assertEquals(
            List.of(Map.of("first_name", "Dr"), Map.of("first_name", "Prof", "hs_priority", "low")), result);
    }

    @Test
    void testTwoApplicationFieldsOnOneIntegrationFieldLastDeclaredWinsOnTheWayBack() {
        FieldMappingDescriptor descriptor = new FieldMappingDescriptor(
            "contacts",
            List.of(
                new FieldMappingDescriptor.Mapping("title", "first_name"),
                new FieldMappingDescriptor.Mapping("salutation", "first_name")));

        Object toIntegration = FieldMappingApplier.apply(
            descriptor, Map.of("title", "Dr", "salutation", "Mr"), FieldMappingDirection.TO_INTEGRATION, false);

        assertEquals(Map.of("first_name", "Mr"), toIntegration);

        Object toApplication = FieldMappingApplier.apply(
            descriptor, Map.of("first_name", "Dr"), FieldMappingDirection.TO_APPLICATION, false);

        assertEquals(Map.of("title", "Dr", "salutation", "Dr"), toApplication);
    }

    @Test
    void testUnsupportedPayloadIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> FieldMappingApplier.apply(DESCRIPTOR, "not an object", FieldMappingDirection.TO_INTEGRATION, false));
    }

    @Test
    void testDirectionMetadata() {
        assertEquals("mapToIntegration", FieldMappingDirection.TO_INTEGRATION.actionName());
        assertEquals("mapToApplication", FieldMappingDirection.TO_APPLICATION.actionName());

        FieldMappingDescriptor.Mapping mapping = new FieldMappingDescriptor.Mapping("title", "first_name");

        assertEquals("title", FieldMappingDirection.TO_INTEGRATION.sourcePath(mapping));
        assertEquals("first_name", FieldMappingDirection.TO_INTEGRATION.destinationPath(mapping));
        assertEquals("first_name", FieldMappingDirection.TO_APPLICATION.sourcePath(mapping));
        assertEquals("title", FieldMappingDirection.TO_APPLICATION.destinationPath(mapping));
    }
}

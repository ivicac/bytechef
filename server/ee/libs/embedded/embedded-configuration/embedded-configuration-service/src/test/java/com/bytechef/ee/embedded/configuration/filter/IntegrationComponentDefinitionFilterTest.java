/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.filter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.constant.PlatformType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class IntegrationComponentDefinitionFilterTest {

    private final IntegrationComponentDefinitionFilter filter = new IntegrationComponentDefinitionFilter();

    /**
     * The embedded pool is unusable from the embedded editor if its components are hidden -- the pool split already
     * keeps embedded authors off automation's resources, so the filter no longer needs to hide these.
     */
    @Test
    void testDataTableAndKnowledgeBaseAreOfferedToIntegrationWorkflows() {
        assertTrue(offers("dataTable"));
        assertTrue(offers("knowledgeBase"));
    }

    @Test
    void testApiPlatformAndWebhookStayHidden() {
        assertFalse(offers("apiPlatform"));
        assertFalse(offers("webhook"));
    }

    @Test
    void testAnOrdinaryConnectorIsStillOffered() {
        assertTrue(offers("slack"));
    }

    /**
     * A second {@code ComponentDefinitionFilter} bean for EMBEDDED would not work --
     * {@code ComponentDefinitionServiceImpl} selects with {@code findFirst()} -- so this filter has to stay the only
     * one, and has to keep supporting only its own platform type.
     */
    @Test
    void testTheFilterSupportsEmbeddedOnly() {
        assertTrue(filter.supports(PlatformType.EMBEDDED));
        assertFalse(filter.supports(PlatformType.AUTOMATION));
    }

    /**
     * Asks the filter about one component through the list form, so the assertion still reads as a question about that
     * component while exercising the signature the service actually calls.
     */
    private boolean offers(String name) {
        List<ComponentDefinition> componentDefinitions = filter.filter(List.of(componentDefinition(name)));

        return !componentDefinitions.isEmpty();
    }

    private static ComponentDefinition componentDefinition(String name) {
        ComponentDefinition componentDefinition = mock(ComponentDefinition.class);

        when(componentDefinition.getName()).thenReturn(name);

        return componentDefinition;
    }
}

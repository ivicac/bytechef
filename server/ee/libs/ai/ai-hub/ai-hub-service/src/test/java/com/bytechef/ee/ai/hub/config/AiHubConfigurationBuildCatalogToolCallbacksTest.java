/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.automation.ai.tool.ProjectWorkflowLifecycleTools;
import com.bytechef.automation.ai.tool.ProjectWorkflowTools;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins that the AI Hub BUILD tool-search catalog registers {@link ProjectWorkflowLifecycleTools} rather than
 * {@link ProjectWorkflowTools}, so no catalog tool writes a workflow definition and {@code buildWorkflow} stays the
 * hub's only path to workflow content.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubConfigurationBuildCatalogToolCallbacksTest {

    @Test
    void testBuildCatalogTakesTheLifecycleToolsInsteadOfProjectWorkflowTools() {
        Method catalogMethod = Arrays.stream(AiHubConfiguration.class.getDeclaredMethods())
            .filter(method -> "aiHubBuildGlobalToolCatalog".equals(method.getName()))
            .findFirst()
            .orElseThrow();

        List<Class<?>> parameterTypes = Arrays.asList(catalogMethod.getParameterTypes());

        assertThat(parameterTypes).contains(ProjectWorkflowLifecycleTools.class)
            .doesNotContain(ProjectWorkflowTools.class);
    }
}

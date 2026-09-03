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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.ComponentDefinitionRegistry;
import com.bytechef.platform.component.context.ContextFactory;
import com.bytechef.platform.component.visibility.ComponentVisibilityProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ClusterElementDefinitionServiceImplVisibilityTest {

    private ComponentDefinitionRegistry componentDefinitionRegistry;
    private ContextFactory contextFactory;
    private ObjectProvider<MeterRegistry> meterRegistryObjectProvider;
    private ClusterElementDefinitionServiceImpl service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ComponentVisibilityProvider disableSlack = componentName -> !componentName.equals("slack");

        componentDefinitionRegistry = mock(ComponentDefinitionRegistry.class);
        contextFactory = mock(ContextFactory.class);
        meterRegistryObjectProvider = mock(ObjectProvider.class);

        service = new ClusterElementDefinitionServiceImpl(
            componentDefinitionRegistry, contextFactory, List.of(disableSlack), meterRegistryObjectProvider);
    }

    @Test
    void testExecuteToolRejectsDisabledComponent() {
        assertThatThrownBy(
            () -> service.executeTool("slack", 1, "sendMessage", Map.of(), null, false))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void testExecuteToolMultipleConnectionsRejectsDisabledComponent() {
        Map<String, ComponentConnection> componentConnections = Map.of();

        assertThatThrownBy(
            () -> service.executeTool("slack", 1, "sendMessage", Map.of(), Map.of(), componentConnections, false))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void testExecuteToolRefusesAnOperationHiddenByPolicy() {
        ComponentVisibilityProvider componentVisibilityProvider = mock(ComponentVisibilityProvider.class);

        when(componentVisibilityProvider.isVisible("slack")).thenReturn(true);
        when(componentVisibilityProvider.isActionVisible("slack", "sendMessage")).thenReturn(false);

        ClusterElementDefinitionServiceImpl service = new ClusterElementDefinitionServiceImpl(
            componentDefinitionRegistry, contextFactory, List.of(componentVisibilityProvider),
            meterRegistryObjectProvider);

        assertThatThrownBy(() -> service.executeTool("slack", 1, "sendMessage", Map.of(), null, false))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("disabled by an administrator");
    }

    @Test
    void testExecuteToolAllowsAVisibleOperation() {
        ComponentVisibilityProvider componentVisibilityProvider = mock(ComponentVisibilityProvider.class);

        when(componentVisibilityProvider.isVisible("slack")).thenReturn(true);
        when(componentVisibilityProvider.isActionVisible("slack", "sendMessage")).thenReturn(true);

        ClusterElementDefinitionServiceImpl service = new ClusterElementDefinitionServiceImpl(
            componentDefinitionRegistry, contextFactory, List.of(componentVisibilityProvider),
            meterRegistryObjectProvider);

        // Reaching the registry lookup (and failing there) proves the visibility guard let the call through.
        assertThatThrownBy(() -> service.executeTool("slack", 1, "sendMessage", Map.of(), null, false))
            .isNotInstanceOf(ConfigurationException.class);
    }
}

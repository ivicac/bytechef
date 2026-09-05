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

package com.bytechef.platform.workflow.task.dispatcher.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.workflow.task.dispatcher.TaskDispatcherDefinitionRegistry;
import com.bytechef.platform.workflow.task.dispatcher.definition.OutputDefinition;
import com.bytechef.platform.workflow.task.dispatcher.definition.PropertiesDataSource;
import com.bytechef.platform.workflow.task.dispatcher.definition.Property;
import com.bytechef.platform.workflow.task.dispatcher.definition.TaskDispatcherDefinition;
import com.bytechef.platform.workflow.task.dispatcher.domain.Option;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class TaskDispatcherDefinitionServiceTest {

    @Mock
    private TaskDispatcherDefinitionRegistry taskDispatcherDefinitionRegistry;

    @Mock
    private TaskDispatcherDefinition taskDispatcherDefinition;

    @Mock
    private Property.DynamicPropertiesProperty dynamicPropertiesProperty;

    @Mock
    private OutputDefinition outputDefinition;

    @Mock
    private PropertiesDataSource propertiesDataSource;

    @Mock
    private Property.StringProperty stringProperty;

    @Mock
    private TaskDispatcherDefinition.VariablePropertiesFunction variablePropertiesFunction;

    private TaskDispatcherDefinitionServiceImpl taskDispatcherDefinitionService;

    @BeforeEach
    void setUp() {
        taskDispatcherDefinitionService = new TaskDispatcherDefinitionServiceImpl(taskDispatcherDefinitionRegistry);
    }

    @Test
    void testExecuteDynamicPropertiesReturnsEmptyWhenNoMatchingProperty() {
        when(taskDispatcherDefinitionRegistry.getTaskDispatcherDefinition("subflow", 1))
            .thenReturn(taskDispatcherDefinition);
        when(taskDispatcherDefinition.getProperties()).thenReturn(Optional.of(List.of()));

        List<com.bytechef.platform.workflow.task.dispatcher.domain.Property> result =
            taskDispatcherDefinitionService.executeDynamicProperties(
                "subflow", 1, "nonExistentProperty", Map.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void testExecuteDynamicPropertiesReturnsEmptyWhenNoProperties() {
        when(taskDispatcherDefinitionRegistry.getTaskDispatcherDefinition("subflow", 1))
            .thenReturn(taskDispatcherDefinition);
        when(taskDispatcherDefinition.getProperties()).thenReturn(Optional.empty());

        List<com.bytechef.platform.workflow.task.dispatcher.domain.Property> result =
            taskDispatcherDefinitionService.executeDynamicProperties(
                "subflow", 1, "inputs", Map.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void testExecuteDynamicPropertiesThrowsConfigurationExceptionOnError() {
        when(taskDispatcherDefinitionRegistry.getTaskDispatcherDefinition("subflow", 1))
            .thenReturn(taskDispatcherDefinition);
        when(dynamicPropertiesProperty.getName()).thenReturn("inputs");
        when(taskDispatcherDefinition.getProperties()).thenReturn(
            Optional.of(List.of(dynamicPropertiesProperty)));
        when(dynamicPropertiesProperty.getDynamicPropertiesDataSource())
            .thenReturn(Optional.of(propertiesDataSource));
        when(propertiesDataSource.getPropertiesFunction())
            .thenReturn(Optional.of(inputParameters -> {
                throw new RuntimeException("test error");
            }));

        ConfigurationException exception = assertThrows(
            ConfigurationException.class,
            () -> taskDispatcherDefinitionService.executeDynamicProperties(
                "subflow", 1, "inputs", Map.of()));

        assertInstanceOf(RuntimeException.class, exception.getCause());
    }

    @Test
    void testExecuteOptionsReturnsEmptyWhenNoMatchingProperty() {
        when(taskDispatcherDefinitionRegistry.getTaskDispatcherDefinition("subflow", 1))
            .thenReturn(taskDispatcherDefinition);
        when(taskDispatcherDefinition.getProperties()).thenReturn(Optional.of(List.of()));

        List<Option> result = taskDispatcherDefinitionService.executeOptions(
            "subflow", 1, "nonExistentProperty", "search");

        assertTrue(result.isEmpty());
    }

    @Test
    void testExecuteOptionsThrowsConfigurationExceptionOnError() {
        when(taskDispatcherDefinitionRegistry.getTaskDispatcherDefinition("subflow", 1))
            .thenReturn(taskDispatcherDefinition);
        when(stringProperty.getName()).thenReturn("workflowUuid");
        when(taskDispatcherDefinition.getProperties()).thenReturn(
            Optional.of(List.of(stringProperty)));
        when(stringProperty.getOptionsFunction())
            .thenReturn(Optional.of(search -> {
                throw new RuntimeException("test error");
            }));

        ConfigurationException exception = assertThrows(
            ConfigurationException.class,
            () -> taskDispatcherDefinitionService.executeOptions(
                "subflow", 1, "workflowUuid", "search"));

        assertInstanceOf(RuntimeException.class, exception.getCause());
    }

    @Test
    void testVariablePropertiesDefinedIsFalseWhenOnlyAnOutputDefinitionIsPresent() {
        when(taskDispatcherDefinitionRegistry.getTaskDispatcherDefinition("subflow", 1))
            .thenReturn(taskDispatcherDefinition);
        when(taskDispatcherDefinition.getName()).thenReturn("subflow");
        when(taskDispatcherDefinition.getOutputDefinition()).thenReturn(Optional.of(outputDefinition));

        com.bytechef.platform.workflow.task.dispatcher.domain.TaskDispatcherDefinition definition =
            taskDispatcherDefinitionService.getTaskDispatcherDefinition("subflow", 1);

        assertTrue(definition.isOutputDefined());
        assertFalse(definition.isVariablePropertiesDefined());
    }

    @Test
    void testVariablePropertiesDefinedIsTrueWhenVariablePropertiesArePresent() {
        when(taskDispatcherDefinitionRegistry.getTaskDispatcherDefinition("loop", 1))
            .thenReturn(taskDispatcherDefinition);
        when(taskDispatcherDefinition.getName()).thenReturn("loop");
        when(taskDispatcherDefinition.getVariableProperties()).thenReturn(Optional.of(variablePropertiesFunction));

        com.bytechef.platform.workflow.task.dispatcher.domain.TaskDispatcherDefinition definition =
            taskDispatcherDefinitionService.getTaskDispatcherDefinition("loop", 1);

        assertTrue(definition.isVariablePropertiesDefined());
    }

    @Test
    void testDynamicOutputStaysDefinedForADispatcherThatOnlyDefinesOutput() {
        when(taskDispatcherDefinitionRegistry.getTaskDispatcherDefinition("subflow", 1))
            .thenReturn(taskDispatcherDefinition);
        when(taskDispatcherDefinition.getName()).thenReturn("subflow");
        when(taskDispatcherDefinition.getOutputDefinition()).thenReturn(Optional.of(outputDefinition));

        assertTrue(taskDispatcherDefinitionService.isDynamicOutputDefined("subflow", 1));
    }
}

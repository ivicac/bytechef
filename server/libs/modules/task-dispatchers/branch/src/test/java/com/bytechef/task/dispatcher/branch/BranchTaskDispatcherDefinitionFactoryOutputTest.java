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

package com.bytechef.task.dispatcher.branch;

import static com.bytechef.platform.workflow.task.dispatcher.definition.TaskDispatcherDsl.string;
import static com.bytechef.platform.workflow.task.dispatcher.output.TaskListOutputDataSource.ENVIRONMENT_ID;
import static com.bytechef.platform.workflow.task.dispatcher.output.TaskListOutputDataSource.WORKFLOW_ID;

import com.bytechef.definition.BaseOutputDefinition.OutputResponse;
import com.bytechef.platform.workflow.task.dispatcher.definition.Property;
import com.bytechef.platform.workflow.task.dispatcher.definition.TaskDispatcherDsl.ModifiableObjectProperty;
import com.bytechef.platform.workflow.task.dispatcher.output.TaskListOutputDataSource;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
public class BranchTaskDispatcherDefinitionFactoryOutputTest {

    @Test
    public void testOutputListsEveryCaseAndDefault() {
        Map<String, Object> inputParameters = Map.of(
            WORKFLOW_ID, "test-workflow",
            ENVIRONMENT_ID, 1L,
            "cases", List.of(caseOf("k1", "var_1"), caseOf("k2", "var_2")),
            "default", List.of(task("var_3")));

        TaskListOutputDataSource dataSource = (workflowId, lastTaskName, lastTaskType, environmentId) -> Map.of(
            "var_1", OutputResponse.of(string(), "first"),
            "var_2", OutputResponse.of(string()),
            "var_3", OutputResponse.of(string(), "fallback"))
            .get(lastTaskName);

        OutputResponse outputResponse = BranchTaskDispatcherDefinitionFactory.output(inputParameters, dataSource);

        Assertions.assertEquals(
            List.of("k1", "k2", "default"), propertyNames((ModifiableObjectProperty) outputResponse.getOutputSchema()));
        Assertions.assertEquals(Map.of("k1", "first", "default", "fallback"), outputResponse.getSampleOutput());
    }

    @Test
    public void testOutputKeepsCaseWithoutTasksAsEmptyObject() {
        Map<String, Object> inputParameters = Map.of(
            WORKFLOW_ID, "test-workflow",
            "cases", List.of(Map.of("key", "k1", "tasks", List.of())),
            "default", List.of());

        TaskListOutputDataSource dataSource = (workflowId, lastTaskName, lastTaskType, environmentId) -> {
            throw new AssertionError("the data source must not be queried for a case without tasks");
        };

        OutputResponse outputResponse = BranchTaskDispatcherDefinitionFactory.output(inputParameters, dataSource);

        Assertions.assertEquals(
            List.of("k1", "default"), propertyNames((ModifiableObjectProperty) outputResponse.getOutputSchema()));
        Assertions.assertNull(outputResponse.getSampleOutput());
    }

    private static Map<String, Object> caseOf(String key, String taskName) {
        return Map.of("key", key, "tasks", List.of(task(taskName)));
    }

    private static List<String> propertyNames(ModifiableObjectProperty objectProperty) {
        return objectProperty.getProperties()
            .stream()
            .map(Property::getName)
            .toList();
    }

    private static Map<String, Object> task(String name) {
        return Map.of("name", name, "type", "var/v1/set");
    }
}

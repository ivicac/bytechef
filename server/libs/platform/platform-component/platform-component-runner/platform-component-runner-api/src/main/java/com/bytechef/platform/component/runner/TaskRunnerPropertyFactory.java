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

package com.bytechef.platform.component.runner;

import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.GRAALVM;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TASK_RUNNER;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TYPE;

import com.bytechef.component.definition.ComponentDsl.ModifiableObjectProperty;
import com.bytechef.component.definition.ComponentDsl.ModifiableOption;
import com.bytechef.component.definition.ComponentDsl.ModifiableValueProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds the {@code taskRunner} object property from the enabled runners.
 *
 * <p>
 * The property is assembled from the registry rather than declared literally, so a runner contributed by another module
 * appears without any component change. The cost is that a component's definition can no longer be read in one file -
 * each action's README must enumerate the built-in runners, because the definition no longer shows them.
 *
 * @author Ivica Cardic
 */
public class TaskRunnerPropertyFactory {

    private TaskRunnerPropertyFactory() {
    }

    /**
     * Builds the {@code taskRunner} property offering every enabled runner that declares all of the required
     * capabilities.
     *
     * @param taskRunnerRegistry   the registry to read runners from
     * @param requiredCapabilities the capabilities an offered runner must declare
     * @return the assembled object property
     */
    public static ModifiableObjectProperty taskRunnerProperty(
        TaskRunnerRegistry taskRunnerRegistry, Set<TaskRunnerCapability> requiredCapabilities) {

        List<TaskRunner> taskRunners = taskRunnerRegistry.getTaskRunners(requiredCapabilities);

        List<ModifiableOption<String>> options = new ArrayList<>();
        List<ModifiableValueProperty<?, ?>> properties = new ArrayList<>();

        for (TaskRunner taskRunner : taskRunners) {
            String type = taskRunner.getType();

            options.add(option(taskRunner.getTitle(), type));

            // getProperties() is contracted to return fresh instances, so stamping in place cannot leak a condition
            // onto a list another action also holds.
            for (ModifiableValueProperty<?, ?> property : taskRunner.getProperties()) {
                property.displayCondition("%s.%s == '%s'".formatted(TASK_RUNNER, TYPE, type));

                properties.add(property);
            }
        }

        List<ModifiableValueProperty<?, ?>> allProperties = new ArrayList<>();

        // An empty options list still leaves the select present but optional - required(true) with nothing to
        // choose would be an unfillable field, whereas an optional, empty select just means perform() later fails
        // with TaskRunnerNotEnabledException, whose message names the exact config key an operator must set.
        allProperties.add(
            string(TYPE)
                .label("Type")
                .description("Where this task runs.")
                .options(options)
                .defaultValue(defaultTaskRunnerType(taskRunners))
                .required(!taskRunners.isEmpty()));

        allProperties.addAll(properties);

        return object(TASK_RUNNER)
            .label("Task Runner")
            .description("Selects the environment this task executes in.")
            .properties(allProperties)
            .required(false)
            .expressionEnabled(false);
    }

    /**
     * Prefers {@link TaskRunnerConstants#GRAALVM} when it is among the enabled runners, so the editor's default matches
     * the runner a workflow falls back to at runtime when it carries no {@code taskRunner} configuration at all -
     * otherwise the same workflow would resolve to a different runner depending on whether it was built in the editor
     * or hand-edited. Falls back to the first enabled runner when GraalVM itself is disabled, and to no default once no
     * runner is enabled.
     */
    private static String defaultTaskRunnerType(List<TaskRunner> taskRunners) {
        if (taskRunners.isEmpty()) {
            return null;
        }

        for (TaskRunner taskRunner : taskRunners) {
            if (GRAALVM.equals(taskRunner.getType())) {
                return GRAALVM;
            }
        }

        return taskRunners.getFirst()
            .getType();
    }
}

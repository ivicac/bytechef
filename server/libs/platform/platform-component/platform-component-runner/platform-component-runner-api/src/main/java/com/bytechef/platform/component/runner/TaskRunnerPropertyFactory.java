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

import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.fileEntry;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.ENV;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.GRAALVM;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.INPUT_FILES;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.OUTPUT_FILES;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TASK_RUNNER;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TIMEOUT;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TYPE;

import com.bytechef.component.definition.ComponentDsl.ModifiableObjectProperty;
import com.bytechef.component.definition.ComponentDsl.ModifiableOption;
import com.bytechef.component.definition.ComponentDsl.ModifiableValueProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

            for (ModifiableValueProperty<?, ?> property : getFreshProperties(taskRunner)) {
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
     * Builds the properties that only an out-of-process runner can honour, each shown for exactly the runners that
     * declare its capability.
     *
     * <p>
     * A property no enabled runner supports is omitted rather than shown disabled: an always-hidden field is
     * indistinguishable from a broken one, and the runner's own {@code validate} rejects the value anyway if a
     * hand-edited workflow supplies it.
     */
    public static List<ModifiableValueProperty<?, ?>> externalProperties(TaskRunnerRegistry taskRunnerRegistry) {
        List<ModifiableValueProperty<?, ?>> properties = new ArrayList<>();

        addIfSupported(
            properties, taskRunnerRegistry, TaskRunnerCapability.ENVIRONMENT,
            object(ENV)
                .label("Environment")
                .description("Environment variables the execution sees. The host's own environment is not inherited.")
                .additionalProperties(string())
                .required(false));

        addIfSupported(
            properties, taskRunnerRegistry, TaskRunnerCapability.INPUT_FILES,
            object(INPUT_FILES)
                .label("Input Files")
                .description(
                    "Files written into the working directory before the execution, keyed by file name. A value may " +
                        "be inline text or a file entry.")
                .additionalProperties(string(), fileEntry())
                .required(false));

        addIfSupported(
            properties, taskRunnerRegistry, TaskRunnerCapability.OUTPUT_FILES,
            array(OUTPUT_FILES)
                .label("Output Files")
                .description("Glob patterns matched against the output directory after the execution.")
                .items(string())
                .required(false));

        addForExternalRunners(
            properties, taskRunnerRegistry,
            integer(TIMEOUT)
                .label("Timeout (seconds)")
                .description("How long the execution may run before it is killed.")
                .required(false));

        return properties;
    }

    private static void addIfSupported(
        List<ModifiableValueProperty<?, ?>> properties, TaskRunnerRegistry taskRunnerRegistry,
        TaskRunnerCapability capability, ModifiableValueProperty<?, ?> property) {

        List<TaskRunner> taskRunners = taskRunnerRegistry.getTaskRunners(Set.of(capability));

        if (taskRunners.isEmpty()) {
            return;
        }

        String condition = taskRunners.stream()
            .map(taskRunner -> "%s.%s == '%s'".formatted(TASK_RUNNER, TYPE, taskRunner.getType()))
            .collect(Collectors.joining(" || "));

        property.displayCondition(condition);

        properties.add(property);
    }

    /**
     * Adds a property shown for every runner that executes outside this JVM.
     *
     * <p>
     * "External" is derived, not listed: a runner is in-process exactly when it can offer the component bridge, which
     * is a live host object and therefore cannot cross a process boundary. So the filter is the absence of
     * {@link TaskRunnerCapability#COMPONENT_BRIDGE}, and a runner contributed by another module lands on the correct
     * side of it without an edit here.
     */
    private static void addForExternalRunners(
        List<ModifiableValueProperty<?, ?>> properties, TaskRunnerRegistry taskRunnerRegistry,
        ModifiableValueProperty<?, ?> property) {

        List<TaskRunner> taskRunners = taskRunnerRegistry.getTaskRunners(Set.of())
            .stream()
            .filter(taskRunner -> {
                Set<TaskRunnerCapability> capabilities = taskRunner.getCapabilities();

                return !capabilities.contains(TaskRunnerCapability.COMPONENT_BRIDGE);
            })
            .toList();

        if (taskRunners.isEmpty()) {
            return;
        }

        String condition = taskRunners.stream()
            .map(taskRunner -> "%s.%s == '%s'".formatted(TASK_RUNNER, TYPE, taskRunner.getType()))
            .collect(Collectors.joining(" || "));

        property.displayCondition(condition);

        properties.add(property);
    }

    /**
     * Reads a runner's properties, rejecting a runner that hands out instances it also keeps.
     *
     * <p>
     * {@link TaskRunner#getProperties()} is contracted to return freshly constructed properties on every call, because
     * the caller stamps a {@code displayCondition} onto each in place. A runner that returns a cached list instead
     * would have that list shared by every component offering it - {@code script} and, from phase 2, {@code commands} -
     * and the sharing is invisible in both definitions, which is what makes the failure worth catching rather than
     * documenting.
     *
     * <p>
     * The factory cannot defend itself by copying: the DSL's {@code Modifiable*} property types have private
     * constructors and no copy API, so a defensive copy would mean a copy constructor on every property type in the
     * SDK. So the contract is verified instead - a second call must yield different instances - and a runner breaking
     * it fails loudly while the definition is assembled, rather than silently at whatever later point the shared
     * instance matters.
     */
    private static List<? extends ModifiableValueProperty<?, ?>> getFreshProperties(TaskRunner taskRunner) {
        List<? extends ModifiableValueProperty<?, ?>> properties = taskRunner.getProperties();
        List<? extends ModifiableValueProperty<?, ?>> otherProperties = taskRunner.getProperties();

        for (ModifiableValueProperty<?, ?> property : properties) {
            for (ModifiableValueProperty<?, ?> otherProperty : otherProperties) {
                if (property == otherProperty) {
                    throw new IllegalStateException(
                        "Task runner '%s' returned a cached property from getProperties(); it must return freshly constructed properties on every call, because a displayCondition is stamped onto them"
                            .formatted(taskRunner.getType()));
                }
            }
        }

        return properties;
    }

    /**
     * Prefers {@link TaskRunnerConstants#GRAALVM} when it is among the enabled runners, so the editor's default matches
     * the runner a workflow falls back to at runtime when it carries no {@code taskRunner} configuration at all.
     *
     * <p>
     * The two agree whenever GraalVM is enabled, which is the shipped default. They cannot agree when it is not: the
     * runtime fallback in {@code ScriptActionDefinition} is the literal {@code graalvm}, while this default is
     * whichever runner the operator did enable. A workflow built in the editor then carries that runner explicitly and
     * runs, and a hand-written workflow with no {@code taskRunner} at all resolves {@code graalvm} and fails with
     * {@link TaskRunnerNotEnabledException}, whose message names the configuration key to set. That is a loud failure
     * on a deliberately unusual configuration, not a silent divergence.
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

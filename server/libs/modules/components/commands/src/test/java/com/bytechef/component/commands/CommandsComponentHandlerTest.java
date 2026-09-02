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

package com.bytechef.component.commands;

import static com.bytechef.platform.component.runner.TaskRunnerConstants.CPU;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.DOCKER;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.ENTRYPOINT;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.ENV;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.EXTRA_HOSTS;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.GRAALVM;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.IMAGE;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.INPUT_FILES;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.MEMORY;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.NETWORK_MODE;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.OUTPUT_FILES;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.PROCESS;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.PULL_POLICY;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TASK_RUNNER;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TIMEOUT;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TYPE;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.definition.Option;
import com.bytechef.component.definition.Property;
import com.bytechef.component.definition.Property.ObjectProperty;
import com.bytechef.component.definition.Property.StringProperty;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.component.runner.GraalVmTaskRunner;
import com.bytechef.platform.component.runner.TaskRunnerRegistry;
import com.bytechef.platform.component.runner.TaskRunnerRegistryImpl;
import com.bytechef.platform.component.runner.external.DockerTaskRunner;
import com.bytechef.platform.component.runner.external.ProcessTaskRunner;
import com.bytechef.test.jsonasssert.JsonFileAssert;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The definition is configuration dependent: the {@code taskRunner} property is assembled from whichever runners the
 * registry reports as enabled, filtered to those declaring {@code COMMANDS}. The registry is therefore built here
 * explicitly - an incidentally empty registry would let the snapshot record no runners at all without anything failing.
 *
 * <p>
 * All three built-in runners are registered AND enabled, and the registry now proves the capability filter from both
 * sides. GraalVM is enabled and must <em>not</em> appear, because it declares no {@code COMMANDS} capability: with only
 * the process runner registered, relaxing the required capability set to {@code Set.of()} would still leave
 * {@code process} as the only option, so the filter would be proved by exclusion rather than by derivation, and only an
 * enabled GraalVM keeps that honest. Docker is enabled and must appear, because it does declare {@code COMMANDS} - so
 * the filter is now shown to be reading capabilities rather than merely dropping everything it was not told about.
 *
 * <p>
 * The registry has a second job the assertions below do not show. {@code generateDocumentation} builds the published
 * reference page's property table from the snapshot this test writes, so a runner absent from this registry is a runner
 * absent from the documentation - which is how {@code image}, {@code pullPolicy} and the rest of Docker's own
 * properties went undocumented while the runner itself shipped.
 *
 * @author Ivica Cardic
 */
class CommandsComponentHandlerTest {

    private static final List<String> ALWAYS_VISIBLE_PROPERTY_NAMES = List.of(
        "commands", TASK_RUNNER, "warnOnStdErr");

    private final ComponentDefinition componentDefinition = new CommandsComponentHandler(
        createTaskRunnerRegistry()).getDefinition();

    @Test
    void testGetDefinition() {
        JsonFileAssert.assertEquals("definition/commands_v1.json", componentDefinition);
    }

    /**
     * Every action's runner select must offer exactly the two runners that declare {@code COMMANDS} - GraalVM is
     * enabled in this registry, so it is absent only because it declares no such capability. Relaxing any action's
     * required capability set fails this test.
     */
    @Test
    void testEveryActionOffersOnlyTheCommandCapableTaskRunners() {
        List<ActionDefinition> actionDefinitions = componentDefinition.getActions();

        assertThat(actionDefinitions).hasSize(3);

        for (ActionDefinition actionDefinition : actionDefinitions) {
            StringProperty typeProperty = findTaskRunnerTypeProperty(actionDefinition);

            List<String> optionValues = typeProperty.getOptions()
                .stream()
                .map(Option::getValue)
                .toList();

            assertThat(optionValues).containsExactly(DOCKER, PROCESS);
            assertThat(typeProperty.getDefaultValue()).contains(DOCKER);
        }
    }

    /**
     * Docker's own properties must reach every action, which is the whole reason it was added to this registry: the
     * published reference page's property table comes from the snapshot, and until Docker was enabled here the page
     * described a runner the component offers without naming a single one of its settings.
     */
    @Test
    void testEveryActionOffersTheDockerRunnerProperties() {
        List<ActionDefinition> actionDefinitions = componentDefinition.getActions();

        for (ActionDefinition actionDefinition : actionDefinitions) {
            ObjectProperty taskRunnerProperty = findTaskRunnerProperty(actionDefinition);

            Map<String, Optional<String>> displayConditions = displayConditionsByName(
                taskRunnerProperty.getProperties());

            assertThat(displayConditions).containsKeys(
                CPU, ENTRYPOINT, EXTRA_HOSTS, IMAGE, MEMORY, NETWORK_MODE, PULL_POLICY, USER);
        }
    }

    /**
     * Replaces the evidence a phase 2 review leaned on. That review read "the snapshot contains none of the external
     * property names" as proof that nothing had leaked past a display condition. Enabling every runner makes those
     * names appear by design, so an absence check is now worthless and this asserts the property that actually matters:
     * every property a runner contributes, at either level, carries a {@code displayCondition}. One appearing without a
     * condition - and so visible whichever runner is selected - still fails.
     */
    @Test
    void testEveryRunnerContributedPropertyCarriesADisplayCondition() {
        List<ActionDefinition> actionDefinitions = componentDefinition.getActions();

        for (ActionDefinition actionDefinition : actionDefinitions) {
            Map<String, Optional<String>> displayConditions = displayConditionsByName(
                actionDefinition.getProperties());

            ALWAYS_VISIBLE_PROPERTY_NAMES.forEach(displayConditions::remove);

            assertThat(displayConditions).containsKeys(ENV, INPUT_FILES, OUTPUT_FILES, TIMEOUT);

            assertDisplayConditionsArePresent(displayConditions, actionDefinition.getName());

            ObjectProperty taskRunnerProperty = findTaskRunnerProperty(actionDefinition);

            Map<String, Optional<String>> runnerDisplayConditions = displayConditionsByName(
                taskRunnerProperty.getProperties());

            runnerDisplayConditions.remove(TYPE);

            assertDisplayConditionsArePresent(runnerDisplayConditions, actionDefinition.getName());
        }
    }

    private static void assertDisplayConditionsArePresent(
        Map<String, Optional<String>> displayConditions, String actionName) {

        for (Map.Entry<String, Optional<String>> entry : displayConditions.entrySet()) {
            assertThat(entry.getValue())
                .as("property '%s' of action '%s' must be shown only for the runners that support it",
                    entry.getKey(), actionName)
                .isPresent();
        }
    }

    private static StringProperty findTaskRunnerTypeProperty(ActionDefinition actionDefinition) {
        ObjectProperty taskRunnerProperty = findTaskRunnerProperty(actionDefinition);

        return taskRunnerProperty.getProperties()
            .stream()
            .filter(property -> TYPE.equals(property.getName()))
            .map(StringProperty.class::cast)
            .findFirst()
            .orElseThrow();
    }

    private static ObjectProperty findTaskRunnerProperty(ActionDefinition actionDefinition) {
        List<? extends Property> properties = actionDefinition.getProperties();

        return properties.stream()
            .filter(property -> TASK_RUNNER.equals(property.getName()))
            .map(ObjectProperty.class::cast)
            .findFirst()
            .orElseThrow();
    }

    private static Map<String, Optional<String>> displayConditionsByName(List<? extends Property> properties) {
        Map<String, Optional<String>> displayConditions = new LinkedHashMap<>();

        for (Property property : properties) {
            displayConditions.put(property.getName(), property.getDisplayCondition());
        }

        return displayConditions;
    }

    private static TaskRunnerRegistry createTaskRunnerRegistry() {
        ApplicationProperties applicationProperties = new ApplicationProperties();

        ApplicationProperties.Script script = applicationProperties.getScript();

        script.setRunners(Map.of(DOCKER, enabledRunner(), GRAALVM, enabledRunner(), PROCESS, enabledRunner()));

        return new TaskRunnerRegistryImpl(
            List.of(
                new DockerTaskRunner(applicationProperties), new GraalVmTaskRunner(null, applicationProperties),
                new ProcessTaskRunner(applicationProperties)),
            applicationProperties);
    }

    private static ApplicationProperties.Script.Runner enabledRunner() {
        ApplicationProperties.Script.Runner runner = new ApplicationProperties.Script.Runner();

        runner.setEnabled(true);

        return runner;
    }
}

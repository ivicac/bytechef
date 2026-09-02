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

import static com.bytechef.component.definition.ComponentDsl.string;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ComponentDsl.ModifiableObjectProperty;
import com.bytechef.component.definition.ComponentDsl.ModifiableStringProperty;
import com.bytechef.component.definition.ComponentDsl.ModifiableValueProperty;
import com.bytechef.component.definition.Option;
import com.bytechef.component.definition.Property;
import com.bytechef.component.definition.Property.ValueProperty;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
public class TaskRunnerPropertyFactoryTest {

    private static final Set<TaskRunnerCapability> INLINE_SCRIPT = Set.of(TaskRunnerCapability.INLINE_SCRIPT);

    @Test
    public void testStampsDisplayConditionOnRunnerProperties() {
        TaskRunnerRegistry taskRunnerRegistry = newRegistry(newTaskRunner("graalvm", "GraalVM", "mode"));

        ModifiableObjectProperty objectProperty = TaskRunnerPropertyFactory.taskRunnerProperty(
            taskRunnerRegistry, INLINE_SCRIPT);

        List<? extends ValueProperty<?>> properties = objectProperty.getProperties();

        ValueProperty<?> modeProperty = properties.stream()
            .filter(property -> "mode".equals(property.getName()))
            .findFirst()
            .orElseThrow();

        assertThat(modeProperty.getDisplayCondition())
            .contains("taskRunner.type == 'graalvm'");
    }

    /**
     * Registers two runners so the assertions can distinguish "assembled in registry order" from "assembled in some
     * order" - a single-runner test cannot fail on either mis-ordering or cross-runner stamping, since there is only
     * one runner for a bug to confuse itself with. Each runner contributes a distinctly-named property so a stamping
     * bug that puts the wrong runner's {@code displayCondition} on the wrong property is visible rather than
     * coincidentally correct.
     */
    @Test
    public void testAssemblesMultipleRunnersInRegistryOrderEachWithItsOwnDisplayCondition() {
        TaskRunner dockerTaskRunner = newTaskRunner("docker", "Docker", "image");
        TaskRunner graalVmTaskRunner = newTaskRunner("graalvm", "GraalVM", "mode");

        TaskRunnerRegistry taskRunnerRegistry = newRegistry(dockerTaskRunner, graalVmTaskRunner);

        ModifiableObjectProperty objectProperty = TaskRunnerPropertyFactory.taskRunnerProperty(
            taskRunnerRegistry, INLINE_SCRIPT);

        List<? extends ValueProperty<?>> properties = objectProperty.getProperties();

        Property.StringProperty typeProperty = (Property.StringProperty) properties.get(0);

        assertThat(typeProperty.getOptions())
            .extracting(Option::getValue)
            .containsExactly("docker", "graalvm");

        assertThat(properties)
            .extracting(ValueProperty::getName)
            .containsExactly("type", "image", "mode");

        ValueProperty<?> imageProperty = properties.get(1);
        ValueProperty<?> modeProperty = properties.get(2);

        assertThat(imageProperty.getDisplayCondition()).contains("taskRunner.type == 'docker'");
        assertThat(modeProperty.getDisplayCondition()).contains("taskRunner.type == 'graalvm'");
    }

    /**
     * The factory stamps the {@code displayCondition} onto the runner's own property objects, so a runner returning a
     * cached list hands the same mutable instance to every component that offers it. Nothing downstream can notice
     * that, and phase 2 is exactly the case the spec named - a runner included by both {@code script} and
     * {@code commands}. Only the one shipped runner is known to honour the contract, so the factory checks it rather
     * than trusting implementations it does not control.
     */
    @Test
    public void testRejectsARunnerReturningCachedProperties() {
        TaskRunner cachingTaskRunner = mock(TaskRunner.class);

        List<ModifiableStringProperty> cachedProperties = List.of(string("mode").label("Mode"));

        when(cachingTaskRunner.getType()).thenReturn("caching");
        when(cachingTaskRunner.getTitle()).thenReturn("Caching");
        when(cachingTaskRunner.getCapabilities()).thenReturn(INLINE_SCRIPT);
        when(cachingTaskRunner.getProperties()).thenAnswer(invocation -> cachedProperties);

        TaskRunnerRegistry taskRunnerRegistry = newRegistry(cachingTaskRunner);

        assertThatThrownBy(() -> TaskRunnerPropertyFactory.taskRunnerProperty(taskRunnerRegistry, INLINE_SCRIPT))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("caching")
            .hasMessageContaining("freshly constructed");
    }

    @Test
    public void testEmptyRegistryProducesAnOptionalSelectWithNoOptionsAndNoDefault() {
        TaskRunnerRegistry taskRunnerRegistry = newRegistry();

        Property.StringProperty typeProperty = getTypeProperty(taskRunnerRegistry);

        assertThat(typeProperty.getRequired()).isFalse();
        assertThat(typeProperty.getOptions()).isEmpty();
        assertThat(typeProperty.getDefaultValue()).isEmpty();
    }

    @Test
    public void testNonEmptyRegistryProducesARequiredSelect() {
        TaskRunnerRegistry taskRunnerRegistry = newRegistry(newTaskRunner("graalvm", "GraalVM", "mode"));

        Property.StringProperty typeProperty = getTypeProperty(taskRunnerRegistry);

        assertThat(typeProperty.getRequired()).isTrue();
    }

    @Test
    public void testDefaultsToGraalVmWhenGraalVmIsAmongTheEnabledRunnersButNotFirst() {
        TaskRunner dockerTaskRunner = newTaskRunner("docker", "Docker", "image");
        TaskRunner graalVmTaskRunner = newTaskRunner("graalvm", "GraalVM", "mode");

        // Registered with GraalVM second, deliberately NOT taskRunners.getFirst(): if defaultTaskRunnerType ever
        // regressed to "the first enabled runner", this would assert "docker" and fail, since docker is what
        // getFirst() actually returns here. Only a genuine GraalVM preference makes "graalvm" come out.
        TaskRunnerRegistry taskRunnerRegistry = newRegistry(dockerTaskRunner, graalVmTaskRunner);

        Property.StringProperty typeProperty = getTypeProperty(taskRunnerRegistry);

        assertThat(typeProperty.getDefaultValue()).contains("graalvm");
    }

    @Test
    public void testDefaultsToTheFirstEnabledRunnerWhenGraalVmIsNotEnabled() {
        TaskRunner dockerTaskRunner = newTaskRunner("docker", "Docker", "image");
        TaskRunner processTaskRunner = newTaskRunner("process", "Process", "commands");

        TaskRunnerRegistry taskRunnerRegistry = newRegistry(dockerTaskRunner, processTaskRunner);

        Property.StringProperty typeProperty = getTypeProperty(taskRunnerRegistry);

        assertThat(typeProperty.getDefaultValue()).contains("docker");
    }

    private static Property.StringProperty getTypeProperty(TaskRunnerRegistry taskRunnerRegistry) {
        ModifiableObjectProperty objectProperty = TaskRunnerPropertyFactory.taskRunnerProperty(
            taskRunnerRegistry, INLINE_SCRIPT);

        return (Property.StringProperty) objectProperty.getProperties()
            .getFirst();
    }

    private static TaskRunner newTaskRunner(String type, String title, String propertyName) {
        TaskRunner taskRunner = mock(TaskRunner.class);

        when(taskRunner.getType()).thenReturn(type);
        when(taskRunner.getTitle()).thenReturn(title);
        when(taskRunner.getCapabilities()).thenReturn(INLINE_SCRIPT);
        when(taskRunner.getProperties())
            .thenAnswer(invocation -> List.of(string(propertyName).label(propertyName)));

        return taskRunner;
    }

    /**
     * Answers any capability set, not just {@link #INLINE_SCRIPT}, by applying the same
     * {@code capabilities.containsAll(requiredCapabilities)} filter {@link TaskRunnerRegistryImpl} itself applies.
     * {@code externalProperties} queries the registry with several different capability sets - one per external
     * property, plus an empty set to find every out-of-process runner - so a stub answering only one fixed set cannot
     * serve those tests, and this is the single registry helper both the older and newer tests share.
     */
    private static TaskRunnerRegistry newRegistry(TaskRunner... taskRunners) {
        TaskRunnerRegistry taskRunnerRegistry = mock(TaskRunnerRegistry.class);

        when(taskRunnerRegistry.getTaskRunners(any()))
            .thenAnswer(invocation -> {
                Set<TaskRunnerCapability> requiredCapabilities = invocation.getArgument(0);

                return List.of(taskRunners)
                    .stream()
                    .filter(taskRunner -> taskRunner.getCapabilities()
                        .containsAll(requiredCapabilities))
                    .toList();
            });

        return taskRunnerRegistry;
    }

    /**
     * A runner that keeps the same JVM, standing in for {@code GraalVmTaskRunner}: it declares
     * {@link TaskRunnerCapability#COMPONENT_BRIDGE}, so {@code externalProperties} must never offer any of the four
     * external properties for it.
     */
    private static TaskRunner inProcessRunner() {
        return newCapableTaskRunner(
            "graalvm", Set.of(TaskRunnerCapability.INLINE_SCRIPT, TaskRunnerCapability.COMPONENT_BRIDGE));
    }

    /**
     * A runner that leaves the JVM, standing in for {@code ProcessTaskRunner}: it declares the same five capabilities
     * that runner does, and none of {@code COMPONENT_BRIDGE} - which is exactly what qualifies it for every external
     * property.
     */
    private static TaskRunner externalRunner() {
        return newCapableTaskRunner(
            "external",
            Set.of(
                TaskRunnerCapability.INLINE_SCRIPT, TaskRunnerCapability.COMMANDS, TaskRunnerCapability.INPUT_FILES,
                TaskRunnerCapability.OUTPUT_FILES, TaskRunnerCapability.ENVIRONMENT));
    }

    /**
     * A runner that declares {@link TaskRunnerCapability#ENVIRONMENT} but neither
     * {@link TaskRunnerCapability#INPUT_FILES} nor {@link TaskRunnerCapability#OUTPUT_FILES}. {@link #externalRunner()}
     * declares all three together, so a test built only from that fixture cannot tell "gated on the right capability"
     * from "gated on any capability at all" - swapping which capability each of the three {@code addIfSupported} calls
     * checks (e.g. gating {@code env} on {@code OUTPUT_FILES} and {@code inputFiles} on {@code ENVIRONMENT}) would
     * still pass every existing assertion. This fixture's asymmetry - one capability present, two absent - is what a
     * swap like that cannot survive.
     */
    private static TaskRunner environmentOnlyRunner() {
        return newCapableTaskRunner(
            "environment-only", Set.of(TaskRunnerCapability.INLINE_SCRIPT, TaskRunnerCapability.ENVIRONMENT));
    }

    private static TaskRunner newCapableTaskRunner(String type, Set<TaskRunnerCapability> capabilities) {
        TaskRunner taskRunner = mock(TaskRunner.class);

        when(taskRunner.getType()).thenReturn(type);
        when(taskRunner.getCapabilities()).thenReturn(capabilities);

        return taskRunner;
    }

    @Test
    void testExternalPropertiesAreOmittedWhenNoRunnerDeclaresTheCapability() {
        TaskRunnerRegistry taskRunnerRegistry = newRegistry(inProcessRunner());

        List<ModifiableValueProperty<?, ?>> properties =
            TaskRunnerPropertyFactory.externalProperties(taskRunnerRegistry);

        assertThat(properties).isEmpty();
    }

    @Test
    void testExternalPropertiesAppearWhenARunnerDeclaresTheCapability() {
        TaskRunnerRegistry taskRunnerRegistry = newRegistry(externalRunner());

        List<String> names = TaskRunnerPropertyFactory.externalProperties(taskRunnerRegistry)
            .stream()
            .map(property -> property.getName())
            .toList();

        assertThat(names).containsExactly("env", "inputFiles", "outputFiles", "timeout");
    }

    @Test
    void testEachExternalPropertyIsConditionedOnTheRunnersThatSupportIt() {
        TaskRunnerRegistry taskRunnerRegistry = newRegistry(externalRunner());

        ModifiableValueProperty<?, ?> envProperty = TaskRunnerPropertyFactory.externalProperties(taskRunnerRegistry)
            .getFirst();

        assertThat(envProperty.getDisplayCondition())
            .hasValueSatisfying(condition -> assertThat(condition).contains("taskRunner.type == 'external'"));
    }

    /**
     * {@link #testExternalPropertiesAppearWhenARunnerDeclaresTheCapability} alone cannot tell "gated on the right
     * capability" apart from "gated on any capability" - {@code externalRunner()} declares
     * {@code ENVIRONMENT}/{@code INPUT_FILES}/{@code OUTPUT_FILES} together, so swapping which of the three
     * {@code addIfSupported} calls checks which capability (e.g. gating {@code env} on {@code OUTPUT_FILES} and
     * {@code inputFiles} on {@code ENVIRONMENT}) would still satisfy it. A runner declaring only {@code ENVIRONMENT}
     * must produce {@code env} and never {@code inputFiles} or {@code outputFiles} - a swap like that produces the
     * wrong set here even though it passes every all-or-nothing fixture.
     */
    @Test
    void testEnvIsGatedOnEnvironmentSpecificallyNotOnInputFilesOrOutputFiles() {
        TaskRunnerRegistry taskRunnerRegistry = newRegistry(environmentOnlyRunner());

        List<String> names = TaskRunnerPropertyFactory.externalProperties(taskRunnerRegistry)
            .stream()
            .map(property -> property.getName())
            .toList();

        // timeout rides along regardless - it gates on the absence of COMPONENT_BRIDGE, not on any of the three
        // per-capability checks this test is discriminating between.
        assertThat(names).containsExactly("env", "timeout");
    }

    /**
     * {@code timeout} is deliberately not gated on a capability - every runner could technically honour a wall clock -
     * so it is the one property whose condition a wrong filter (e.g. gating on a capability instead of the absence of
     * {@code COMPONENT_BRIDGE}) could satisfy by accident. Registering both an in-process and an external runner, then
     * asserting the condition names only the external one, is what catches that: gating on the wrong thing would either
     * omit the property entirely or name both runners.
     */
    @Test
    void testTimeoutIsConditionedOnTheExternalRunnerAndNotOnGraalVm() {
        TaskRunnerRegistry taskRunnerRegistry = newRegistry(inProcessRunner(), externalRunner());

        List<ModifiableValueProperty<?, ?>> properties =
            TaskRunnerPropertyFactory.externalProperties(taskRunnerRegistry);

        ModifiableValueProperty<?, ?> timeoutProperty = properties.stream()
            .filter(property -> "timeout".equals(property.getName()))
            .findFirst()
            .orElseThrow();

        assertThat(timeoutProperty.getDisplayCondition())
            .hasValueSatisfying(condition -> {
                assertThat(condition).contains("taskRunner.type == 'external'");
                assertThat(condition).doesNotContain("taskRunner.type == 'graalvm'");
            });
    }
}

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ComponentDsl.ModifiableObjectProperty;
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

    private static TaskRunnerRegistry newRegistry(TaskRunner... taskRunners) {
        TaskRunnerRegistry taskRunnerRegistry = mock(TaskRunnerRegistry.class);

        when(taskRunnerRegistry.getTaskRunners(INLINE_SCRIPT))
            .thenReturn(List.of(taskRunners));

        return taskRunnerRegistry;
    }
}

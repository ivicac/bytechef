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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ComponentDsl.ModifiableValueProperty;
import com.bytechef.component.definition.Parameters;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.polyglot.ScriptSandboxMode;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
public class GraalVmTaskRunnerTest {

    private static final String SCRIPT = "function perform(input, context) { return null; }";
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final PolyglotEngine polyglotEngine = mock(PolyglotEngine.class);
    private final GraalVmTaskRunner graalVmTaskRunner = newTaskRunner(Map.of());

    @Test
    public void testTypeAndCapabilities() {
        assertThat(graalVmTaskRunner.getType()).isEqualTo("graalvm");
        assertThat(graalVmTaskRunner.getCapabilities())
            .containsExactlyInAnyOrder(
                TaskRunnerCapability.INLINE_SCRIPT, TaskRunnerCapability.COMPONENT_BRIDGE);
    }

    @Test
    public void testGetPropertiesReturnsFreshInstances() {
        List<? extends ModifiableValueProperty<?, ?>> firstProperties = graalVmTaskRunner.getProperties();
        List<? extends ModifiableValueProperty<?, ?>> secondProperties = graalVmTaskRunner.getProperties();

        assertThat(firstProperties).hasSize(1);
        assertThat(secondProperties).hasSize(1);
        assertThat(firstProperties.getFirst()).isNotSameAs(secondProperties.getFirst());
    }

    @Test
    public void testRunReturnsPerformResult() {
        when(polyglotEngine.execute(any(), any(), anyString(), any(), anyMap(), any())).thenReturn("done");

        TaskRunnerResult taskRunnerResult = graalVmTaskRunner.run(newRequest(Map.of()));

        assertThat(taskRunnerResult.output()).isEqualTo("done");
        assertThat(taskRunnerResult.exitCode()).isNull();
        assertThat(taskRunnerResult.outputFiles()).isEmpty();
    }

    /**
     * Pins mode propagation, timeout propagation and argument order together: the request's two {@link Parameters} hold
     * disjoint keys, so forwarding the runner parameters where the input parameters belong fails on the captured value
     * rather than passing silently.
     */
    @Test
    public void testRunForwardsModeTimeoutAndInputParameters() {
        GraalVmTaskRunner trustingTaskRunner = newTaskRunner(Map.of("trusted-enabled", "true"));

        ArgumentCaptor<Parameters> inputParametersCaptor = ArgumentCaptor.forClass(Parameters.class);

        when(
            polyglotEngine.execute(
                eq(ScriptSandboxMode.TRUSTED), eq(TIMEOUT), eq("js"), inputParametersCaptor.capture(), anyMap(),
                any())).thenReturn("done");

        TaskRunnerResult taskRunnerResult = trustingTaskRunner.run(newRequest(Map.of("mode", "trusted")));

        assertThat(taskRunnerResult.output()).isEqualTo("done");

        Parameters capturedInputParameters = inputParametersCaptor.getValue();

        assertThat(capturedInputParameters.getString("script")).isEqualTo(SCRIPT);
        assertThat(capturedInputParameters.getString("mode")).isNull();
    }

    @Test
    public void testValidateRejectsInputFiles() {
        TaskRunnerRequest request = new TaskRunnerRequest(
            "js", SCRIPT, List.of(), Map.of(), Map.of(),
            Map.of("data.csv", "a,b"), List.of(), ParametersFactory.create(Map.of()),
            ParametersFactory.create(Map.of()), TIMEOUT, Map.of(), null);

        assertThatThrownBy(() -> graalVmTaskRunner.validate(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inputFiles");
    }

    @Test
    public void testValidateRejectsOutputFilePatterns() {
        TaskRunnerRequest request = new TaskRunnerRequest(
            "js", SCRIPT, List.of(), Map.of(), Map.of(), Map.of(), List.of("*.csv"),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()), TIMEOUT, Map.of(), null);

        assertThatThrownBy(() -> graalVmTaskRunner.validate(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("outputFiles");
    }

    @Test
    public void testValidateRejectsEnv() {
        TaskRunnerRequest request = new TaskRunnerRequest(
            "js", SCRIPT, List.of(), Map.of(), Map.of("TOKEN", "secret"), Map.of(), List.of(),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()), TIMEOUT, Map.of(), null);

        assertThatThrownBy(() -> graalVmTaskRunner.validate(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("env");
    }

    @Test
    public void testValidateRejectsCommands() {
        TaskRunnerRequest request = new TaskRunnerRequest(
            "js", null, List.of("echo hi"), Map.of(), Map.of(), Map.of(), List.of(),
            ParametersFactory.create(Map.of()), ParametersFactory.create(Map.of()),
            TIMEOUT, Map.of(), null);

        assertThatThrownBy(() -> graalVmTaskRunner.validate(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("commands");
    }

    @Test
    public void testValidateRejectsUnknownMode() {
        TaskRunnerRequest request = newRequest(Map.of("mode", "permissive"));

        assertThatThrownBy(() -> graalVmTaskRunner.validate(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("permissive");
    }

    @Test
    public void testValidateAcceptsStrictAndDefaultMode() {
        graalVmTaskRunner.validate(newRequest(Map.of("mode", "strict")));
        graalVmTaskRunner.validate(newRequest(Map.of()));
    }

    @Test
    public void testValidateAcceptsTrustedWhenTrustedEnabled() {
        GraalVmTaskRunner trustingTaskRunner = newTaskRunner(Map.of("trusted-enabled", "true"));

        assertThatCode(() -> trustingTaskRunner.validate(newRequest(Map.of("mode", "trusted"))))
            .doesNotThrowAnyException();
    }

    @Test
    public void testValidateRejectsTrustedWhenTrustedDisabled() {
        GraalVmTaskRunner disallowingTaskRunner = newTaskRunner(Map.of("trusted-enabled", "false"));

        assertThatThrownBy(() -> disallowingTaskRunner.validate(newRequest(Map.of("mode", "trusted"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bytechef.script.runners.graalvm.properties.trusted-enabled");
    }

    @Test
    public void testValidateRejectsTrustedWhenTrustedPropertyAbsent() {
        assertThatThrownBy(() -> graalVmTaskRunner.validate(newRequest(Map.of("mode", "trusted"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bytechef.script.runners.graalvm.properties.trusted-enabled");
    }

    @Test
    public void testValidateRejectsTrustedWhenTrustedPropertyMalformed() {
        GraalVmTaskRunner malformedTaskRunner = newTaskRunner(Map.of("trusted-enabled", "yes"));

        assertThatThrownBy(() -> malformedTaskRunner.validate(newRequest(Map.of("mode", "trusted"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bytechef.script.runners.graalvm.properties.trusted-enabled");
    }

    @Test
    public void testValidateRejectsTrustedWhenRunnerAbsentFromConfiguration() {
        GraalVmTaskRunner unconfiguredTaskRunner = new GraalVmTaskRunner(
            polyglotEngine, new ApplicationProperties());

        assertThatThrownBy(() -> unconfiguredTaskRunner.validate(newRequest(Map.of("mode", "trusted"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bytechef.script.runners.graalvm.properties.trusted-enabled");
    }

    @Test
    public void testValidateAcceptsStrictWhateverTheTrustedProperty() {
        for (Map<String, String> properties : List.of(
            Map.<String, String>of(), Map.of("trusted-enabled", "false"), Map.of("trusted-enabled", "true"))) {

            GraalVmTaskRunner taskRunner = newTaskRunner(properties);

            assertThatCode(() -> taskRunner.validate(newRequest(Map.of("mode", "strict"))))
                .doesNotThrowAnyException();
        }
    }

    private GraalVmTaskRunner newTaskRunner(Map<String, String> graalVmProperties) {
        ApplicationProperties applicationProperties = new ApplicationProperties();

        ApplicationProperties.Script.Runner runner = new ApplicationProperties.Script.Runner();

        runner.setEnabled(true);
        runner.setProperties(new HashMap<>(graalVmProperties));

        applicationProperties.getScript()
            .getRunners()
            .put("graalvm", runner);

        return new GraalVmTaskRunner(polyglotEngine, applicationProperties);
    }

    private static TaskRunnerRequest newRequest(Map<String, ?> runnerParameters) {
        return new TaskRunnerRequest(
            "js", SCRIPT, List.of(), Map.of(), Map.of(), Map.of(), List.of(),
            ParametersFactory.create(Map.of("script", SCRIPT, "input", Map.of("factor", 3))),
            ParametersFactory.create(runnerParameters), TIMEOUT, Map.of(), null);
    }
}

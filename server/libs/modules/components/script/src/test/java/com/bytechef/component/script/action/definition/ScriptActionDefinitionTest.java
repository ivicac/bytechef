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

package com.bytechef.component.script.action.definition;

import static com.bytechef.platform.component.definition.ScriptComponentDefinition.SCRIPT;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.DOCKER;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.ENV;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.GRAALVM;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.INPUT_FILES;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.MODE;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.OUTPUT_FILES;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TASK_RUNNER;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TIMEOUT;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TRUSTED;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.Parameters;
import com.bytechef.component.script.action.ScriptJavaScriptAction;
import com.bytechef.platform.component.definition.MultipleConnectionsPerformFunction;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.runner.TaskRunner;
import com.bytechef.platform.component.runner.TaskRunnerCapability;
import com.bytechef.platform.component.runner.TaskRunnerNotEnabledException;
import com.bytechef.platform.component.runner.TaskRunnerRegistry;
import com.bytechef.platform.component.runner.TaskRunnerRequest;
import com.bytechef.platform.component.runner.TaskRunnerResult;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * Pins the action to the registry. Every test here fails if {@code perform} ever goes back to calling
 * {@code PolyglotEngine} directly: the registry is a mock, so bypassing it means no stubbed runner is reached and no
 * stubbed output comes back.
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
public class ScriptActionDefinitionTest {

    private static final String SOURCE = "function perform(input, context) { return null; }";

    private final TaskRunnerRegistry taskRunnerRegistry = mock(TaskRunnerRegistry.class);
    private final TaskRunner taskRunner = mock(TaskRunner.class);
    private final ScriptActionDefinition scriptActionDefinition = ScriptJavaScriptAction.of(taskRunnerRegistry);

    /**
     * Every test below models an in-process runner such as GraalVM, so {@code taskRunner} declares
     * {@code COMPONENT_BRIDGE} by default here - the one test that models an external runner
     * ({@link #testPerformReturnsTheFullResultForARunnerWithoutTheComponentBridge()}) overrides this stub with a
     * capability set that omits it.
     */
    @BeforeEach
    void setUp() {
        when(taskRunner.getCapabilities()).thenReturn(Set.of(TaskRunnerCapability.COMPONENT_BRIDGE));
    }

    @Test
    public void testPerformResolvesGraalVmWhenTheWorkflowSelectsNoRunner() throws Exception {
        when(taskRunnerRegistry.getTaskRunner(GRAALVM)).thenReturn(taskRunner);
        when(taskRunner.run(any())).thenReturn(TaskRunnerResult.ofOutput("output"));

        Object output = perform(Map.of(SCRIPT, SOURCE, "input", Map.of("factor", 3)));

        assertThat(output).isEqualTo("output");

        verify(taskRunnerRegistry).getTaskRunner(GRAALVM);
    }

    @Test
    public void testPerformResolvesGraalVmWhenTheSelectedTypeIsBlank() throws Exception {
        when(taskRunnerRegistry.getTaskRunner(GRAALVM)).thenReturn(taskRunner);
        when(taskRunner.run(any())).thenReturn(TaskRunnerResult.ofOutput("output"));

        Object output = perform(Map.of(SCRIPT, SOURCE, TASK_RUNNER, Map.of(TYPE, "")));

        assertThat(output).isEqualTo("output");

        verify(taskRunnerRegistry).getTaskRunner(GRAALVM);
    }

    /**
     * DOCKER resolves to an out-of-process runner by definition - the component bridge is a live host object that
     * cannot cross a process boundary, so no runner reached through a docker-style type string will ever declare
     * {@code COMPONENT_BRIDGE}. Nothing enforces that today (no {@code DockerTaskRunner} exists yet), so this test
     * gives the mock the capability shape that type implies rather than inheriting the {@code @BeforeEach} default
     * modeled on GraalVM - otherwise it would assert the bare-value branch for a runner whose real shape is the
     * five-key map, and Phase 3 would land a `DockerTaskRunner` that silently fails what this test claims to cover.
     */
    @Test
    public void testPerformResolvesTheSelectedRunnerType() throws Exception {
        when(taskRunnerRegistry.getTaskRunner(DOCKER)).thenReturn(taskRunner);
        when(taskRunner.getCapabilities())
            .thenReturn(Set.of(TaskRunnerCapability.INLINE_SCRIPT, TaskRunnerCapability.COMMANDS));
        when(taskRunner.run(any())).thenReturn(new TaskRunnerResult(Map.of("greeting", "hi"), 0, "hi", "", Map.of()));

        Object output = perform(Map.of(SCRIPT, SOURCE, TASK_RUNNER, Map.of(TYPE, DOCKER)));

        assertThat(output).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) output;

        assertThat(result)
            .containsEntry("exitCode", 0)
            .containsEntry("stdout", "hi")
            .containsEntry("stderr", "")
            .containsEntry("vars", Map.of("greeting", "hi"))
            .containsEntry("outputFiles", Map.of());

        verify(taskRunnerRegistry).getTaskRunner(DOCKER);
    }

    /**
     * The request must carry the action's own parameters and the runner's own sub-map separately. Asserting on the
     * captured request is what keeps the argument order honest, since several neighbouring arguments share a type -
     * env/inputFiles/outputFiles/timeout sit right next to input/runnerParameters/inputParameters in the constructor
     * call, so a positional slip there would otherwise compile and pass silently.
     */
    @Test
    public void testPerformBuildsTheRequestFromBothParameterMaps() throws Exception {
        when(taskRunnerRegistry.getTaskRunner(GRAALVM)).thenReturn(taskRunner);
        when(taskRunner.run(any())).thenReturn(TaskRunnerResult.ofOutput("output"));

        perform(
            Map.of(
                SCRIPT, SOURCE, "input", Map.of("factor", 3),
                ENV, Map.of("API_KEY", "secret"),
                INPUT_FILES, Map.of("data.csv", "a,b"),
                OUTPUT_FILES, List.of("*.csv"),
                TIMEOUT, 30,
                TASK_RUNNER, Map.of(TYPE, GRAALVM, MODE, TRUSTED)));

        ArgumentCaptor<TaskRunnerRequest> requestCaptor = ArgumentCaptor.forClass(TaskRunnerRequest.class);

        verify(taskRunner).run(requestCaptor.capture());

        TaskRunnerRequest taskRunnerRequest = requestCaptor.getValue();

        assertThat(taskRunnerRequest.languageId()).isEqualTo("js");
        assertThat(taskRunnerRequest.script()).isEqualTo(SOURCE);
        assertThat(taskRunnerRequest.commands()).isEmpty();
        assertThat(taskRunnerRequest.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(taskRunnerRequest.env()).containsEntry("API_KEY", "secret");
        assertThat(taskRunnerRequest.inputFiles()
            .get("data.csv")).isEqualTo("a,b");
        assertThat(taskRunnerRequest.outputFilePatterns()).containsExactly("*.csv");

        Map<String, ?> input = taskRunnerRequest.input();

        assertThat(input.get("factor")).isEqualTo(3);

        Parameters runnerParameters = taskRunnerRequest.runnerParameters();

        assertThat(runnerParameters.getString(MODE)).isEqualTo(TRUSTED);

        Parameters inputParameters = taskRunnerRequest.inputParameters();

        assertThat(inputParameters.getString(SCRIPT)).isEqualTo(SOURCE);
        assertThat(inputParameters.getString(MODE)).isNull();
    }

    /**
     * The companion of {@link #testPerformBuildsTheRequestFromBothParameterMaps}: when the workflow supplies none of
     * the four, the request must carry the empty defaults, not null maps/lists that would NPE downstream.
     */
    @Test
    public void testPerformDefaultsTimeoutToNullWhenAbsent() throws Exception {
        when(taskRunnerRegistry.getTaskRunner(GRAALVM)).thenReturn(taskRunner);
        when(taskRunner.run(any())).thenReturn(TaskRunnerResult.ofOutput("output"));

        perform(Map.of(SCRIPT, SOURCE));

        ArgumentCaptor<TaskRunnerRequest> requestCaptor = ArgumentCaptor.forClass(TaskRunnerRequest.class);

        verify(taskRunner).run(requestCaptor.capture());

        TaskRunnerRequest taskRunnerRequest = requestCaptor.getValue();

        assertThat(taskRunnerRequest.timeout()).isNull();
        assertThat(taskRunnerRequest.env()).isEmpty();
        assertThat(taskRunnerRequest.inputFiles()).isEmpty();
        assertThat(taskRunnerRequest.outputFilePatterns()).isEmpty();
    }

    /**
     * The operator allowlist has to reach the workflow. This is the check that fails if the action stops going through
     * the registry, because a disabled runner is only observable there.
     */
    @Test
    public void testPerformPropagatesTheAllowlistRejection() {
        when(taskRunnerRegistry.getTaskRunner(GRAALVM)).thenThrow(new TaskRunnerNotEnabledException(GRAALVM));

        assertThatThrownBy(() -> perform(Map.of(SCRIPT, SOURCE)))
            .isInstanceOf(TaskRunnerNotEnabledException.class)
            .hasMessageContaining(GRAALVM);
    }

    /**
     * A task with no source used to reach a per-language stub returning null, so a malformed workflow produced nothing
     * and reported nothing. The source is required now, and the failure names the parameter.
     */
    @Test
    public void testPerformRejectsAMissingScript() {
        when(taskRunnerRegistry.getTaskRunner(GRAALVM)).thenReturn(taskRunner);

        assertThatThrownBy(() -> perform(Map.of("input", Map.of("factor", 3))))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining(SCRIPT);

        verify(taskRunner, never()).run(any());
    }

    /**
     * GraalVM offers {@code COMPONENT_BRIDGE}, so {@code perform} must hand back the bare value the script returned -
     * not the {@code {exitCode, stdout, stderr, vars, outputFiles}} shape an external runner produces.
     */
    @Test
    public void testPerformReturnsTheBareValueForARunnerWithTheComponentBridge() throws Exception {
        when(taskRunnerRegistry.getTaskRunner(GRAALVM)).thenReturn(taskRunner);
        when(taskRunner.run(any())).thenReturn(TaskRunnerResult.ofOutput("output"));

        Object output = perform(Map.of(SCRIPT, SOURCE));

        assertThat(output).isEqualTo("output");
    }

    /**
     * A runner without {@code COMPONENT_BRIDGE} - standing in for the process runner - never crosses the JVM boundary
     * with a live host object, so {@code perform} must hand back the full external result instead of just
     * {@code output()}. This test fails if the capability check in {@code perform} is ever inverted: with the check
     * flipped, this stub (no {@code COMPONENT_BRIDGE}) would take the bare-value branch instead, and the assertion
     * below on the map's keys would fail.
     */
    @Test
    public void testPerformReturnsTheFullResultForARunnerWithoutTheComponentBridge() throws Exception {
        when(taskRunnerRegistry.getTaskRunner(GRAALVM)).thenReturn(taskRunner);
        when(taskRunner.getCapabilities()).thenReturn(Set.of(TaskRunnerCapability.INLINE_SCRIPT));
        when(taskRunner.run(any())).thenReturn(
            new TaskRunnerResult(Map.of("total", 6), 0, "hello", "", Map.of()));

        Object output = perform(Map.of(SCRIPT, SOURCE));

        assertThat(output).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) output;

        assertThat(result)
            .containsEntry("exitCode", 0)
            .containsEntry("stdout", "hello")
            .containsEntry("stderr", "")
            .containsEntry("vars", Map.of("total", 6))
            .containsEntry("outputFiles", Map.of());
    }

    /**
     * {@code TaskRunnerResult.exitCode()} is {@code @Nullable}, and {@code TaskRunnerResult.ofOutput(...)} - used
     * throughout this file to stand in for a bare-value result - produces exactly that null. {@code Map.of(...)} throws
     * an NPE the moment any argument is null, so this is what makes the result-building code's choice of
     * {@code HashMap} load-bearing: every other test here happens to supply a non-null exit code, so reverting to
     * {@code Map.of(...)} would pass them all and only fail here, at runtime, not at compile time.
     */
    @Test
    public void testPerformReturnsTheFullResultWhenTheExitCodeIsNull() throws Exception {
        when(taskRunnerRegistry.getTaskRunner(GRAALVM)).thenReturn(taskRunner);
        when(taskRunner.getCapabilities()).thenReturn(Set.of(TaskRunnerCapability.INLINE_SCRIPT));
        when(taskRunner.run(any())).thenReturn(TaskRunnerResult.ofOutput("output"));

        Object output = perform(Map.of(SCRIPT, SOURCE));

        assertThat(output).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) output;

        assertThat(result).containsKey("exitCode");
        assertThat(result.get("exitCode")).isNull();
    }

    private Object perform(Map<String, ?> inputParameters) throws Exception {
        MultipleConnectionsPerformFunction performFunction = scriptActionDefinition.getPerform()
            .orElseThrow();

        return performFunction.apply(
            ParametersFactory.create(inputParameters), Map.of(), ParametersFactory.create(Map.of()), null);
    }
}

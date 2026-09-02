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
import static com.bytechef.platform.component.runner.TaskRunnerConstants.GRAALVM;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.MODE;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TASK_RUNNER;
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
import com.bytechef.platform.component.runner.TaskRunnerNotEnabledException;
import com.bytechef.platform.component.runner.TaskRunnerRegistry;
import com.bytechef.platform.component.runner.TaskRunnerRequest;
import com.bytechef.platform.component.runner.TaskRunnerResult;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.Map;
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

    @Test
    public void testPerformResolvesTheSelectedRunnerType() throws Exception {
        when(taskRunnerRegistry.getTaskRunner(DOCKER)).thenReturn(taskRunner);
        when(taskRunner.run(any())).thenReturn(TaskRunnerResult.ofOutput("output"));

        Object output = perform(Map.of(SCRIPT, SOURCE, TASK_RUNNER, Map.of(TYPE, DOCKER)));

        assertThat(output).isEqualTo("output");

        verify(taskRunnerRegistry).getTaskRunner(DOCKER);
    }

    /**
     * The request must carry the action's own parameters and the runner's own sub-map separately, and no timeout - the
     * runner decides that. Asserting on the captured request is what keeps the argument order honest, since several
     * neighbouring arguments share a type.
     */
    @Test
    public void testPerformBuildsTheRequestFromBothParameterMaps() throws Exception {
        when(taskRunnerRegistry.getTaskRunner(GRAALVM)).thenReturn(taskRunner);
        when(taskRunner.run(any())).thenReturn(TaskRunnerResult.ofOutput("output"));

        perform(
            Map.of(
                SCRIPT, SOURCE, "input", Map.of("factor", 3),
                TASK_RUNNER, Map.of(TYPE, GRAALVM, MODE, TRUSTED)));

        ArgumentCaptor<TaskRunnerRequest> requestCaptor = ArgumentCaptor.forClass(TaskRunnerRequest.class);

        verify(taskRunner).run(requestCaptor.capture());

        TaskRunnerRequest taskRunnerRequest = requestCaptor.getValue();

        assertThat(taskRunnerRequest.languageId()).isEqualTo("js");
        assertThat(taskRunnerRequest.script()).isEqualTo(SOURCE);
        assertThat(taskRunnerRequest.commands()).isEmpty();
        assertThat(taskRunnerRequest.timeout()).isNull();

        Map<String, ?> input = taskRunnerRequest.input();

        assertThat(input.get("factor")).isEqualTo(3);

        Parameters runnerParameters = taskRunnerRequest.runnerParameters();

        assertThat(runnerParameters.getString(MODE)).isEqualTo(TRUSTED);

        Parameters inputParameters = taskRunnerRequest.inputParameters();

        assertThat(inputParameters.getString(SCRIPT)).isEqualTo(SOURCE);
        assertThat(inputParameters.getString(MODE)).isNull();
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

    private Object perform(Map<String, ?> inputParameters) throws Exception {
        MultipleConnectionsPerformFunction performFunction = scriptActionDefinition.getPerform()
            .orElseThrow();

        return performFunction.apply(
            ParametersFactory.create(inputParameters), Map.of(), ParametersFactory.create(Map.of()), null);
    }
}

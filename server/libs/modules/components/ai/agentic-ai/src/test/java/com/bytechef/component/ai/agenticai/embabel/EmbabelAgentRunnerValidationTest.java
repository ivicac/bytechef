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

package com.bytechef.component.ai.agenticai.embabel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.DomainInstanceKt;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Covers the pre-platform validation rules in {@link EmbabelAgentRunner#run} and the goal-result extraction for both
 * carrier shapes (untyped {@link Binding} content, type-tagged maps of typed bindings). The validation rules encode the
 * safety guarantees called out in the commit history (unreachable goal, unreachable entry point, duplicate action
 * names, producer/consumer schema agreement) and must fail fast with actionable messages instead of deferring to an
 * opaque Embabel runtime error.
 *
 * @author Ivica Cardic
 */
class EmbabelAgentRunnerValidationTest {

    private static final String ACTION_NAME = "generate-result";
    private static final String ACTION_DESCRIPTION = "Generate the final result";
    private static final String ACTION_PROMPT = "Produce the requested output";
    private static final String USER_GOAL_BINDING = "userGoal";
    private static final String RESULT_BINDING = "result";
    private static final double DEFAULT_COST = 1.0;

    private final AgentPlatform agentPlatform = mock(AgentPlatform.class);
    private final EmbabelAgentRunner runner = new EmbabelAgentRunner(agentPlatform);

    @Test
    void testEmptyActionStepsRejected() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> runner.run(List.of(), "goal", RESULT_BINDING, false, null));

        assertThat(exception.getMessage()).contains("action step");
    }

    @Test
    void testBlankGoalOutputBindingRejected() {
        List<ActionStep> actionSteps = List.of(
            new ActionStep(
                ACTION_NAME, ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, RESULT_BINDING, List.of(),
                DEFAULT_COST));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> runner.run(actionSteps, "goal", "   ", false, null));

        assertThat(exception.getMessage()).contains("goalOutputBinding");
    }

    @Test
    void testUnreachableGoalOutputBindingRejected() {
        List<ActionStep> actionSteps = List.of(
            new ActionStep(
                ACTION_NAME, ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, "intermediate", List.of(),
                DEFAULT_COST));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> runner.run(actionSteps, "goal", RESULT_BINDING, false, null));

        assertThat(exception.getMessage()).contains(RESULT_BINDING);
    }

    @Test
    void testMissingUserGoalEntryPointRejected() {
        List<ActionStep> actionSteps = List.of(
            new ActionStep(
                ACTION_NAME, ACTION_DESCRIPTION, ACTION_PROMPT, "unreachable", RESULT_BINDING, List.of(),
                DEFAULT_COST));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> runner.run(actionSteps, "goal", RESULT_BINDING, false, null));

        assertThat(exception.getMessage()).contains(USER_GOAL_BINDING);
    }

    @Test
    void testDuplicateActionNamesRejected() {
        List<ActionStep> actionSteps = List.of(
            new ActionStep(
                "same", ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, RESULT_BINDING, List.of(), DEFAULT_COST),
            new ActionStep(
                "same", ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, RESULT_BINDING, List.of(),
                DEFAULT_COST));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> runner.run(actionSteps, "goal", RESULT_BINDING, false, null));

        assertThat(exception.getMessage()).contains("Duplicate");
    }

    @Test
    void testColonInBindingNameRejected() {
        List<ActionStep> actionSteps = List.of(
            new ActionStep(
                ACTION_NAME, ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, "result:Typed", List.of(),
                DEFAULT_COST));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> runner.run(actionSteps, "goal", "result:Typed", false, null));

        assertThat(exception.getMessage()).contains("':'");
    }

    @Test
    void testMixedTypedAndUntypedProducersRejected() {
        List<ActionStep> actionSteps = List.of(
            new ActionStep(
                "typed-producer", ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, RESULT_BINDING, List.of(),
                DEFAULT_COST, List.of(new OutputProperty("title", "string", "The title"))),
            new ActionStep(
                "untyped-producer", ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, RESULT_BINDING, List.of(),
                DEFAULT_COST));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> runner.run(actionSteps, "goal", RESULT_BINDING, false, null));

        assertThat(exception.getMessage()).contains(RESULT_BINDING)
            .contains("agree");
    }

    @Test
    void testConflictingOutputSchemasRejected() {
        List<ActionStep> actionSteps = List.of(
            new ActionStep(
                "producer-one", ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, RESULT_BINDING, List.of(),
                DEFAULT_COST, List.of(new OutputProperty("title", "string", null))),
            new ActionStep(
                "producer-two", ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, RESULT_BINDING, List.of(),
                DEFAULT_COST, List.of(new OutputProperty("summary", "string", null))));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> runner.run(actionSteps, "goal", RESULT_BINDING, false, null));

        assertThat(exception.getMessage()).contains("different output schemas");
    }

    @Test
    void testDuplicateSchemaPropertyNamesRejected() {
        List<ActionStep> actionSteps = List.of(
            new ActionStep(
                ACTION_NAME, ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, RESULT_BINDING, List.of(),
                DEFAULT_COST,
                List.of(new OutputProperty("title", "string", null), new OutputProperty("title", "number", null))));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> runner.run(actionSteps, "goal", RESULT_BINDING, false, null));

        assertThat(exception.getMessage()).contains("duplicate");
    }

    @Test
    void testUserGoalOutputSchemaRejected() {
        List<ActionStep> actionSteps = List.of(
            new ActionStep(
                "rewrites-goal", ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, USER_GOAL_BINDING, List.of(),
                DEFAULT_COST, List.of(new OutputProperty("title", "string", null))),
            new ActionStep(
                ACTION_NAME, ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, RESULT_BINDING, List.of(),
                DEFAULT_COST));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> runner.run(actionSteps, "goal", RESULT_BINDING, false, null));

        assertThat(exception.getMessage()).contains(USER_GOAL_BINDING);
    }

    @Test
    void testUntypedGoalResultReturnsContent() {
        AgentProcess agentProcess = mock(AgentProcess.class);

        when(agentPlatform.runAgentFrom(any(), any(), anyMap())).thenReturn(agentProcess);
        when(agentProcess.get(RESULT_BINDING)).thenReturn(new Binding("plain text result"));

        List<ActionStep> actionSteps = List.of(
            new ActionStep(
                ACTION_NAME, ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, RESULT_BINDING, List.of(),
                DEFAULT_COST));

        Object result = runner.run(actionSteps, "goal", RESULT_BINDING, false, null);

        assertThat(result).isEqualTo("plain text result");
    }

    @Test
    void testTypedGoalResultReturnsStructuredMap() {
        AgentProcess agentProcess = mock(AgentProcess.class);

        Map<String, Object> taggedMap = new LinkedHashMap<>();

        taggedMap.put(DomainInstanceKt.TYPE_NAME_KEY, "Result");
        taggedMap.put("title", "Structured");
        taggedMap.put("score", 42);

        when(agentPlatform.runAgentFrom(any(), any(), anyMap())).thenReturn(agentProcess);
        when(agentProcess.get(RESULT_BINDING)).thenReturn(taggedMap);

        List<ActionStep> actionSteps = List.of(
            new ActionStep(
                ACTION_NAME, ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, RESULT_BINDING, List.of(),
                DEFAULT_COST,
                List.of(
                    new OutputProperty("title", "string", "The title"),
                    new OutputProperty("score", "integer", "The score"))));

        Object result = runner.run(actionSteps, "goal", RESULT_BINDING, false, null);

        assertThat(result).isEqualTo(Map.of("title", "Structured", "score", 42));
    }

    @Test
    void testEmptyTypedGoalResultRejected() {
        AgentProcess agentProcess = mock(AgentProcess.class);

        when(agentPlatform.runAgentFrom(any(), any(), anyMap())).thenReturn(agentProcess);
        when(agentProcess.get(RESULT_BINDING)).thenReturn(Map.of(DomainInstanceKt.TYPE_NAME_KEY, "Result"));

        List<ActionStep> actionSteps = List.of(
            new ActionStep(
                ACTION_NAME, ACTION_DESCRIPTION, ACTION_PROMPT, USER_GOAL_BINDING, RESULT_BINDING, List.of(),
                DEFAULT_COST, List.of(new OutputProperty("title", "string", null))));

        AgenticAiGoalNotAchievedException exception = assertThrows(
            AgenticAiGoalNotAchievedException.class,
            () -> runner.run(actionSteps, "goal", RESULT_BINDING, false, null));

        assertThat(exception.getMessage()).contains("empty object");
    }
}

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

import static com.bytechef.component.script.constant.ScriptConstants.INPUT;
import static com.bytechef.platform.component.definition.ScriptComponentDefinition.SCRIPT;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.ENV;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.GRAALVM;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.INPUT_FILES;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.OUTPUT_FILES;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.RESULT_EXIT_CODE;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.RESULT_STDERR;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.RESULT_STDOUT;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.RESULT_VARS;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TASK_RUNNER;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TIMEOUT;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TYPE;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.component.definition.Parameters;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.AbstractActionDefinitionWrapper;
import com.bytechef.platform.component.definition.MultipleConnectionsPerformFunction;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.runner.TaskRunner;
import com.bytechef.platform.component.runner.TaskRunnerCapability;
import com.bytechef.platform.component.runner.TaskRunnerRegistry;
import com.bytechef.platform.component.runner.TaskRunnerRequest;
import com.bytechef.platform.component.runner.TaskRunnerResult;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Executes the action's source in the environment its {@code taskRunner} property selects.
 *
 * <p>
 * A workflow carrying no {@code taskRunner} map at all, or one whose type was left empty, falls back to
 * {@code graalvm}. That is the same default the editor stamps onto a newly added action whenever GraalVM is enabled,
 * which is the shipped configuration, so the two agree there. They diverge on one configuration and only one: with
 * GraalVM disabled and another runner enabled, {@code TaskRunnerPropertyFactory} defaults the editor to that other
 * runner, while this fallback still resolves {@code graalvm}. A hand-written workflow with no {@code taskRunner} then
 * fails with {@link com.bytechef.platform.component.runner.TaskRunnerNotEnabledException}, whose message names the
 * configuration key an operator must set. Loud, and confined to a deliberately unusual configuration.
 *
 * <p>
 * The source is read with {@code getRequiredString}, so a task with no {@code script} parameter fails with a message
 * naming the parameter. It used to fall through to a per-language stub that returned null - a malformed workflow
 * producing nothing and reporting nothing.
 *
 * <p>
 * The action carries an optional {@code timeout}. A null one leaves the ceiling to the selected runner; GraalVM strict
 * deliberately applies none, because {@code sandbox.MaxCPUTime} meters CPU rather than elapsed time, and a wall clock
 * would kill a script waiting on a slow HTTP call through {@code context.component.*} while it burns almost no CPU.
 *
 * @author Matija Petanjek
 * @author Ivica Cardic
 */
public class ScriptActionDefinition extends AbstractActionDefinitionWrapper {

    private final String languageId;
    private final TaskRunnerRegistry taskRunnerRegistry;

    public ScriptActionDefinition(
        ActionDefinition actionDefinition, String languageId, TaskRunnerRegistry taskRunnerRegistry) {

        super(actionDefinition);

        this.languageId = languageId;
        this.taskRunnerRegistry = taskRunnerRegistry;
    }

    @Override
    public Optional<MultipleConnectionsPerformFunction> getPerform() {
        return Optional.of(this::perform);
    }

    protected Object perform(
        Parameters inputParameters, Map<String, ComponentConnection> connectionParameters,
        Parameters extensions, ActionContext context) {

        Parameters runnerParameters = ParametersFactory.create(
            inputParameters.getMap(TASK_RUNNER, Object.class, Map.of()));

        String type = runnerParameters.getString(TYPE, GRAALVM);

        TaskRunner taskRunner = taskRunnerRegistry.getTaskRunner(type.isBlank() ? GRAALVM : type);

        Integer timeoutSeconds = inputParameters.getInteger(TIMEOUT);

        TaskRunnerRequest taskRunnerRequest = new TaskRunnerRequest(
            languageId, inputParameters.getRequiredString(SCRIPT), List.of(),
            inputParameters.getMap(INPUT, Object.class, Map.of()),
            inputParameters.getMap(ENV, String.class, Map.of()),
            inputParameters.getMap(INPUT_FILES, Object.class, Map.of()),
            inputParameters.getList(OUTPUT_FILES, String.class, List.of()), inputParameters, runnerParameters,
            timeoutSeconds == null ? null : Duration.ofSeconds(timeoutSeconds), connectionParameters, context);

        TaskRunnerResult taskRunnerResult = taskRunner.run(taskRunnerRequest);

        Set<TaskRunnerCapability> capabilities = taskRunner.getCapabilities();

        if (capabilities.contains(TaskRunnerCapability.COMPONENT_BRIDGE)) {
            return taskRunnerResult.output();
        }

        Map<String, Object> result = new HashMap<>();

        result.put(RESULT_EXIT_CODE, taskRunnerResult.exitCode());
        result.put(RESULT_STDOUT, taskRunnerResult.stdout());
        result.put(RESULT_STDERR, taskRunnerResult.stderr());
        result.put(RESULT_VARS, taskRunnerResult.output());
        result.put(OUTPUT_FILES, taskRunnerResult.outputFiles());

        return result;
    }
}

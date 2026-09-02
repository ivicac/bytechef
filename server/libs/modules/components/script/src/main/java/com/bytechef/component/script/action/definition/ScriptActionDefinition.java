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
import static com.bytechef.platform.component.runner.TaskRunnerConstants.GRAALVM;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TASK_RUNNER;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TYPE;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ActionDefinition;
import com.bytechef.component.definition.Parameters;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.AbstractActionDefinitionWrapper;
import com.bytechef.platform.component.definition.MultipleConnectionsPerformFunction;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.runner.TaskRunner;
import com.bytechef.platform.component.runner.TaskRunnerRegistry;
import com.bytechef.platform.component.runner.TaskRunnerRequest;
import com.bytechef.platform.component.runner.TaskRunnerResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * The request carries no timeout. Phase 1 gives the action no timeout property, and how long an unbounded execution may
 * run is a property of the environment it runs in, not of the action - so the selected runner decides.
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

        TaskRunnerRequest taskRunnerRequest = new TaskRunnerRequest(
            languageId, inputParameters.getRequiredString(SCRIPT), List.of(),
            inputParameters.getMap(INPUT, Object.class, Map.of()), Map.of(), Map.of(), List.of(), inputParameters,
            runnerParameters, null, connectionParameters, context);

        TaskRunnerResult taskRunnerResult = taskRunner.run(taskRunnerRequest);

        return taskRunnerResult.output();
    }
}

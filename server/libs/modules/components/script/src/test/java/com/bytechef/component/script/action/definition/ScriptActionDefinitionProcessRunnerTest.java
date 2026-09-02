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
import static com.bytechef.platform.component.runner.TaskRunnerConstants.PROCESS;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TASK_RUNNER;
import static com.bytechef.platform.component.runner.TaskRunnerConstants.TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.script.action.ScriptJavaScriptAction;
import com.bytechef.component.script.action.ScriptPythonAction;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.platform.component.definition.MultipleConnectionsPerformFunction;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.runner.TaskRunnerRegistry;
import com.bytechef.platform.component.runner.external.ProcessTaskRunner;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs a script action end to end through the real {@link ProcessTaskRunner}, with the language id the action itself
 * passes rather than one typed in here.
 *
 * <p>
 * That is the whole point of this class. {@code ProcessTaskRunnerTest} hand-writes its own ids, and
 * {@code ScriptActionDefinitionTest} asserts the id against a mocked runner - so both passed while the JavaScript
 * action's {@code js} met a runner that only knew {@code javascript}, and every JavaScript script action run under the
 * process runner failed with {@code Language 'js' cannot be executed by an external task runner}. Nothing below names a
 * language id: the ids reach the runner from the action definitions, and a rename on either side fails here.
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class ScriptActionDefinitionProcessRunnerTest {

    private final TaskRunnerRegistry taskRunnerRegistry = mock(TaskRunnerRegistry.class);
    private final ProcessTaskRunner processTaskRunner = new ProcessTaskRunner(new ApplicationProperties());

    @Test
    void testTheJavaScriptActionRunsThroughTheProcessRunner() throws Exception {
        assumeTrue(isOnPath("node"));

        when(taskRunnerRegistry.getTaskRunner(PROCESS)).thenReturn(processTaskRunner);

        Object output = perform(
            ScriptJavaScriptAction.of(taskRunnerRegistry),
            "function perform(input, context) { return {doubled: input.value * 2}; }");

        assertThat(output).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) output;

        assertThat(result)
            .containsEntry("exitCode", 0)
            .containsEntry("vars", Map.of("doubled", 42));
    }

    /**
     * The companion the bug hid behind: {@code python} happened to satisfy both vocabularies, so this passed throughout
     * and made the JavaScript failure look like a JavaScript problem rather than an id problem.
     */
    @Test
    void testThePythonActionRunsThroughTheProcessRunner() throws Exception {
        assumeTrue(isOnPath("python3"));

        when(taskRunnerRegistry.getTaskRunner(PROCESS)).thenReturn(processTaskRunner);

        Object output = perform(
            ScriptPythonAction.of(taskRunnerRegistry),
            "def perform(input, context):\n    return {\"doubled\": input[\"value\"] * 2}\n");

        assertThat(output).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) output;

        assertThat(result)
            .containsEntry("exitCode", 0)
            .containsEntry("vars", Map.of("doubled", 42));
    }

    /**
     * Whether the command is resolvable through the same {@code PATH} the child process is handed, which is the only
     * {@code PATH} that decides whether the interpreter these tests need can be started at all.
     */
    private static boolean isOnPath(String command) {
        String path = System.getenv("PATH");

        if (path == null) {
            return false;
        }

        return Stream.of(path.split(File.pathSeparator))
            .filter(entry -> !entry.isBlank())
            .map(entry -> Path.of(entry)
                .resolve(command))
            .anyMatch(Files::isExecutable);
    }

    private static Object perform(ScriptActionDefinition scriptActionDefinition, String script) throws Exception {
        MultipleConnectionsPerformFunction performFunction = scriptActionDefinition.getPerform()
            .orElseThrow();

        return performFunction.apply(
            ParametersFactory.create(
                Map.of(
                    SCRIPT, script, INPUT, Map.of("value", 21), TASK_RUNNER, Map.of(TYPE, PROCESS))),
            Map.of(), ParametersFactory.create(Map.of()), mock(ActionContext.class));
    }
}

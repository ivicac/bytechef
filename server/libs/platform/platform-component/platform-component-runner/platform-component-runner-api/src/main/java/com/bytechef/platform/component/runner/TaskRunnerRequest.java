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

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.Parameters;
import com.bytechef.platform.component.ComponentConnection;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything a {@link TaskRunner} needs to execute one task.
 *
 * <p>
 * The record carries the union of what every runner may need, and each runner rejects in {@link TaskRunner#validate}
 * what it cannot honour. That is deliberate: one request type keeps one registry, one dispatch point for the allowlist,
 * and an explicit error instead of a silently ignored setting.
 *
 * @param languageId           the language the script is written in, or the interpreter for commands
 * @param script               inline source, or null when commands are given
 * @param commands             the commands to run, or an empty list when a script is given
 * @param input                values the script reads as its input argument
 * @param env                  environment variables the execution should see
 * @param inputFiles           files to materialise into the working directory, keyed by file name
 * @param outputFilePatterns   glob patterns matched against the output directory after the execution
 * @param inputParameters      the action's own parameters, which an in-process runner reads the source from
 * @param runnerParameters     the selected runner's own configuration, from the taskRunner property
 * @param timeout              the wall-clock ceiling for the execution
 * @param componentConnections connections the script may reach through the component bridge
 * @param actionContext        the action context, used for file storage and logging
 * @author Ivica Cardic
 */
@SuppressFBWarnings("EI")
public record TaskRunnerRequest(
    String languageId, @Nullable String script, List<String> commands, Map<String, ?> input, Map<String, String> env,
    Map<String, ?> inputFiles, List<String> outputFilePatterns, Parameters inputParameters,
    Parameters runnerParameters, Duration timeout, Map<String, ComponentConnection> componentConnections,
    ActionContext actionContext) {

    public TaskRunnerRequest {
        if (script == null && commands.isEmpty()) {
            throw new IllegalArgumentException("either script or commands must be given");
        }

        if (script != null && !commands.isEmpty()) {
            throw new IllegalArgumentException("script and commands are mutually exclusive");
        }
    }
}

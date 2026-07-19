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

package com.bytechef.component.daytona.action;

import static com.bytechef.component.daytona.constant.DaytonaConstants.CODE;
import static com.bytechef.component.daytona.constant.DaytonaConstants.LANGUAGE;
import static com.bytechef.component.daytona.constant.DaytonaConstants.TIMEOUT;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context.Http;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.Property.ControlType;
import com.bytechef.component.definition.TypeReference;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a snippet of AI-generated code inside a fresh, isolated Daytona sandbox and returns the result. Designed to
 * be attached to the AI Agent as a tool so the agent can generate code on the fly and run it safely.
 *
 * <p>
 * The action creates an ephemeral sandbox, runs the code via the sandbox toolbox's process code-run endpoint, and
 * deletes the sandbox afterward so no state leaks between invocations.
 * </p>
 *
 * <p>
 * The Daytona REST paths and request/response field names follow Daytona's documented SDK/API conventions. The
 * confirmed response fields are {@code exitCode}, {@code result} (stdout), and {@code artifacts.stdout}. Toolbox
 * routing (the {@code /toolbox/{sandboxId}/toolbox/...} prefix) should be verified against your account's API version.
 * </p>
 *
 * @author Ivica Cardic
 */
public class DaytonaExecuteCodeAction {

    private static final Logger log = LoggerFactory.getLogger(DaytonaExecuteCodeAction.class);

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("executeCode")
        .title("Execute Code")
        .description(
            "Generates and runs code in a secure, isolated Daytona sandbox and returns its output. Use this to " +
                "execute AI-generated code (data analysis, calculations, scripts) safely and get the result back.")
        .properties(
            string(LANGUAGE)
                .label("Language")
                .description("The programming language of the code to execute.")
                .options(
                    option("Python", "python"),
                    option("TypeScript", "typescript"),
                    option("JavaScript", "javascript"),
                    option("Bash", "bash"))
                .defaultValue("python")
                .required(true),
            string(CODE)
                .label("Code")
                .description("The source code to execute in the sandbox.")
                .controlType(ControlType.CODE_EDITOR)
                .required(true),
            integer(TIMEOUT)
                .label("Timeout")
                .description("Maximum time in seconds to wait for the code to finish executing.")
                .defaultValue(60)
                .minValue(1)
                .maxValue(3600)
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("exitCode")
                            .description("The exit code of the executed code (0 indicates success)."),
                        string("stdout")
                            .description("The standard output produced by the code."),
                        bool("success")
                            .description("Whether the code finished with a zero exit code."))))
        .perform(DaytonaExecuteCodeAction::perform);

    private DaytonaExecuteCodeAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, ActionContext context) {
        String language = inputParameters.getString(LANGUAGE, "python");
        String code = inputParameters.getRequiredString(CODE);
        Integer timeout = inputParameters.getInteger(TIMEOUT);

        String sandboxId = createSandbox(context, language);

        try {
            Map<String, Object> response = runCode(context, sandboxId, language, code, timeout);

            return toResult(response);
        } finally {
            deleteSandbox(context, sandboxId);
        }
    }

    /**
     * Creates a fresh sandbox and returns its id. Endpoint: {@code POST /sandbox}.
     */
    private static String createSandbox(ActionContext context, String language) {
        Map<String, Object> body = new LinkedHashMap<>();

        body.put(LANGUAGE, language);

        Map<String, Object> response = context
            .http(http -> http.post("/sandbox"))
            .body(Http.Body.of(body))
            .configuration(Http.responseType(Http.ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<Map<String, Object>>() {});

        Object id = response.get("id");

        if (id == null) {
            throw new IllegalStateException("Daytona did not return a sandbox id");
        }

        return String.valueOf(id);
    }

    /**
     * Runs code inside the sandbox and returns the raw code-run response. Endpoint:
     * {@code POST /toolbox/{sandboxId}/toolbox/process/code-run}.
     */
    private static Map<String, Object> runCode(
        ActionContext context, String sandboxId, String language, String code, Integer timeout) {

        Map<String, Object> body = new LinkedHashMap<>();

        body.put(LANGUAGE, language);
        body.put(CODE, code);

        if (timeout != null) {
            body.put(TIMEOUT, timeout);
        }

        return context
            .http(http -> http.post("/toolbox/" + sandboxId + "/toolbox/process/code-run"))
            .body(Http.Body.of(body))
            .configuration(Http.responseType(Http.ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Best-effort sandbox teardown so no sandbox is leaked when the run finishes or fails. Endpoint:
     * {@code DELETE /sandbox/{sandboxId}}.
     */
    private static void deleteSandbox(ActionContext context, String sandboxId) {
        try {
            context
                .http(http -> http.delete("/sandbox/" + sandboxId))
                .configuration(Http.responseType(Http.ResponseType.JSON))
                .execute();
        } catch (Exception exception) {
            log.warn("Failed to delete Daytona sandbox {}", sandboxId, exception);
        }
    }

    /**
     * Maps a Daytona code-run response onto the action output. Daytona returns {@code exitCode} and {@code result}
     * (stdout); {@code result} may also be surfaced under {@code artifacts.stdout}.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> toResult(Map<String, Object> response) {
        int exitCode = 0;

        Object exitCodeValue = response.get("exitCode");

        if (exitCodeValue instanceof Number number) {
            exitCode = number.intValue();
        }

        String stdout = null;

        Object result = response.get("result");

        if (result != null) {
            stdout = String.valueOf(result);
        } else if (response.get("artifacts") instanceof Map<?, ?> artifacts) {
            Object artifactStdout = ((Map<String, Object>) artifacts).get("stdout");

            if (artifactStdout != null) {
                stdout = String.valueOf(artifactStdout);
            }
        }

        Map<String, Object> output = new LinkedHashMap<>();

        output.put("exitCode", exitCode);
        output.put("stdout", stdout == null ? "" : stdout);
        output.put("success", exitCode == 0);

        return output;
    }
}

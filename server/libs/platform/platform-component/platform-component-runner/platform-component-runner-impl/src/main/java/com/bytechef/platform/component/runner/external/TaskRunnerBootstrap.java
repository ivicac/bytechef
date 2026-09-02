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

package com.bytechef.platform.component.runner.external;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Bridges the {@code perform(input, context)} authoring shape to the file-based contract an external interpreter can
 * honour.
 *
 * <p>
 * The generated code is <strong>appended</strong> to the user's source, never prepended. Prepending shifts every line,
 * so a syntax error on the author's third line is reported at the thirtieth line of a file they never wrote - close to
 * undiagnosable from a container log. Appending costs nothing, because both languages resolve {@code perform} at call
 * time from a definition that appears above.
 *
 * <p>
 * {@code context} is a stub rather than {@code null}. Passing null would surface as
 * {@code TypeError: Cannot read properties of null}, which says nothing about why; the stub raises a sentence naming
 * the runner and the fact that the component bridge is in-process only.
 *
 * @author Ivica Cardic
 */
public final class TaskRunnerBootstrap {

    private static final String JAVASCRIPT = "javascript";
    private static final String PYTHON = "python";
    private static final String SHELL = "shell";

    private TaskRunnerBootstrap() {
    }

    /**
     * Returns the user's source with the language's bootstrap appended, or the source unchanged for a language that
     * needs none.
     */
    public static String append(String languageId, String source, String runnerType) {
        String separated = source.endsWith("\n") ? source : source + "\n";

        return switch (languageId) {
            case JAVASCRIPT -> separated + javaScriptBootstrap(runnerType);
            case PYTHON -> separated + pythonBootstrap(runnerType);
            case SHELL -> separated;
            default -> throw new IllegalArgumentException(
                "Language '%s' cannot be executed by an external task runner".formatted(languageId));
        };
    }

    /**
     * Whether an external runner can execute the language at all.
     *
     * <p>
     * A runner's {@code validate} asks this so it rejects an unrunnable language before it creates anything, rather
     * than letting {@link #append} throw once the working directory already exists.
     */
    public static boolean isSupported(String languageId) {
        return switch (languageId) {
            case JAVASCRIPT, PYTHON, SHELL -> true;
            default -> false;
        };
    }

    /**
     * The name the source is written under inside the working directory.
     */
    public static String sourceFileName(String languageId) {
        return switch (languageId) {
            case JAVASCRIPT -> "script.js";
            case PYTHON -> "script.py";
            case SHELL -> "commands.sh";
            default -> throw new IllegalArgumentException(
                "Language '%s' cannot be executed by an external task runner".formatted(languageId));
        };
    }

    @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    private static String javaScriptBootstrap(String runnerType) {
        return """

            /* ByteChef bootstrap - appended, so the lines above keep their numbers. */
            (async function bytechefMain() {
                const bytechefFs = require('fs');
                const bytechefPath = require('path');
                const bytechefInput = JSON.parse(
                    bytechefFs.readFileSync(process.env.BYTECHEF_INPUT_FILE, 'utf8'));
                const bytechefContext = new Proxy({}, {
                    get(target, property) {
                        throw new Error(
                            'context.' + String(property) + ' is not available under the %s runner');
                    }
                });
                const bytechefResult = await perform(bytechefInput, bytechefContext);

                bytechefFs.writeFileSync(
                    bytechefPath.join(process.env.BYTECHEF_OUTPUT_DIR, 'output.json'),
                    JSON.stringify(bytechefResult === undefined ? null : bytechefResult));
            })().catch(function (error) {
                console.error(error && error.stack ? error.stack : String(error));
                process.exit(1);
            });
            """.formatted(runnerType);
    }

    @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    private static String pythonBootstrap(String runnerType) {
        return """

            # ByteChef bootstrap - appended, so the lines above keep their numbers.
            def _bytechef_main():
                import json
                import os

                class _BytechefContext:
                    def __getattr__(self, name):
                        raise RuntimeError(
                            "context." + name + " is not available under the %s runner")

                with open(os.environ["BYTECHEF_INPUT_FILE"], "r") as bytechef_input_file:
                    bytechef_input = json.load(bytechef_input_file)

                bytechef_result = perform(bytechef_input, _BytechefContext())

                bytechef_output_path = os.path.join(os.environ["BYTECHEF_OUTPUT_DIR"], "output.json")

                with open(bytechef_output_path, "w") as bytechef_output_file:
                    json.dump(bytechef_result, bytechef_output_file)


            _bytechef_main()
            """.formatted(runnerType);
    }
}

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

/**
 * Names shared by the SPI, the components that offer runners, and the {@code displayCondition} expressions that reveal
 * a runner's own properties.
 *
 * <p>
 * The built-in type strings are constants rather than an enum on purpose. A runner defined outside this module must be
 * able to declare its own type without an edit here.
 *
 * @author Ivica Cardic
 */
public class TaskRunnerConstants {

    public static final String TASK_RUNNER = "taskRunner";
    public static final String TYPE = "type";

    public static final String DOCKER = "docker";
    public static final String GRAALVM = "graalvm";
    public static final String PROCESS = "process";

    public static final String MODE = "mode";
    public static final String STRICT = "strict";
    public static final String TRUSTED = "trusted";

    public static final String INHERIT_ENVIRONMENT = "inheritEnvironment";
    public static final String INTERPRETER = "interpreter";

    /**
     * The Docker runner's own property names. They live here rather than on the runner for the same reason every other
     * runner's do - a {@code displayCondition} expression names them as plain text, and a component that has never
     * heard of Docker still assembles those expressions.
     */
    public static final String CPU = "cpu";
    public static final String ENTRYPOINT = "entrypoint";
    public static final String EXTRA_HOSTS = "extraHosts";
    public static final String IMAGE = "image";
    public static final String MEMORY = "memory";
    public static final String NETWORK_MODE = "networkMode";
    public static final String PULL_POLICY = "pullPolicy";
    public static final String USER = "user";

    public static final String ENV = "env";
    public static final String INPUT_FILES = "inputFiles";
    public static final String OUTPUT_FILES = "outputFiles";
    public static final String TIMEOUT = "timeout";

    /**
     * Keys of the result map an external runner's action returns - {@code {exitCode, stdout, stderr, vars,
     * outputFiles}}, per the design spec. {@code script}'s actions produce this shape today; {@code commands}' actions
     * (phase 2) must produce the identical shape, so both reach these constants from here rather than each declaring
     * its own literals and drifting apart. {@link #OUTPUT_FILES} above doubles as the fifth key - the result's
     * {@code outputFiles} and the {@code outputFiles} property both name the same concept, so there is nothing a
     * separate constant would disambiguate.
     */
    public static final String RESULT_EXIT_CODE = "exitCode";
    public static final String RESULT_STDOUT = "stdout";
    public static final String RESULT_STDERR = "stderr";
    public static final String RESULT_VARS = "vars";

    private TaskRunnerConstants() {
    }
}

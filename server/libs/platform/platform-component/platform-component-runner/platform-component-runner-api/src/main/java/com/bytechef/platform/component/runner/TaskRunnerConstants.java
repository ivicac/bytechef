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

    private TaskRunnerConstants() {
    }
}

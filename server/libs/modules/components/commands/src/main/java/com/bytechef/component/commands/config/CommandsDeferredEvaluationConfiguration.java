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

package com.bytechef.component.commands.config;

import static com.bytechef.component.commands.constant.CommandsConstants.COMMANDS;

import com.bytechef.atlas.configuration.domain.DeferredEvaluationParameterKeys;
import org.springframework.context.annotation.Configuration;

/**
 * Holds the {@code commands} parameter back from the workflow expression evaluator.
 *
 * <p>
 * POSIX shell parameter expansion is spelled {@code ${VAR}}, which is character-for-character the evaluator's accessor
 * syntax. Left evaluated, {@code echo ${HOME}} fails the task with {@code Invalid expression} - a message naming
 * neither this component nor this property. Workflow data reaches a command through the {@code env} property instead,
 * whose values are evaluated normally.
 *
 * <p>
 * This registration is eager on purpose. Components load lazily, so a static initialiser on the handler may not have
 * run by the time a task is evaluated, and the deferral would apply only after something else happened to load the
 * component first.
 *
 * @author Ivica Cardic
 */
@Configuration
public class CommandsDeferredEvaluationConfiguration {

    static {
        DeferredEvaluationParameterKeys.register(COMMANDS + "/", COMMANDS);
    }
}

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

import com.bytechef.config.ApplicationProperties;
import java.util.Map;

/**
 * Reads one boolean escape hatch out of a runner's free-form operator properties.
 *
 * <p>
 * Every such hatch lifts a restriction a workflow author must not be able to lift on their own - trusted GraalVM mode,
 * the process runner's environment inheritance - so they all have to fail the same way when the configuration arrives
 * half-written. The parse therefore lives here rather than once per hatch.
 *
 * <p>
 * The parse is fail-closed. An absent runner entry, an absent or null properties map, an absent property, an empty
 * value and a malformed one such as {@code yes} all read as disabled. What opens the gate is
 * {@link Boolean#parseBoolean}, which is case-insensitive: {@code true}, {@code TRUE} and {@code True} all enable it,
 * and nothing else does.
 *
 * @author Ivica Cardic
 */
public final class TaskRunnerOperatorFlag {

    private TaskRunnerOperatorFlag() {
    }

    public static boolean isEnabled(
        ApplicationProperties applicationProperties, String type, String propertyName) {

        ApplicationProperties.Script script = applicationProperties.getScript();

        // Both maps bind null when their YAML key is written with an empty body, and this one is read while the
        // editor assembles a runner's properties as well as while a request is validated. Failing closed on either
        // keeps a half-written configuration from throwing where a decision belongs.
        Map<String, ApplicationProperties.Script.Runner> runners = script.getRunners();

        if (runners == null) {
            return false;
        }

        ApplicationProperties.Script.Runner runner = runners.get(type);

        if (runner == null) {
            return false;
        }

        Map<String, String> properties = runner.getProperties();

        if (properties == null) {
            return false;
        }

        return Boolean.parseBoolean(properties.get(propertyName));
    }

    /**
     * The fully qualified configuration key an operator would set, for use in an error an operator has to act on.
     */
    public static String getPropertyName(String type, String propertyName) {
        return "bytechef.script.runners.%s.properties.%s".formatted(type, propertyName);
    }
}

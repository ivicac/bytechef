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
 * Reads the operator's per-runner {@code trusted-enabled} escape hatch.
 *
 * <p>
 * Two callers read the same setting for different reasons - {@link TaskRunnerRegistryImpl} to decide whether to warn at
 * startup, and {@link GraalVmTaskRunner} to decide whether to reject a request asking for trusted mode - and they must
 * never disagree, so the parse lives here rather than in both.
 *
 * <p>
 * The parse is fail-closed. An absent runner entry, an absent or null properties map, an absent property, an empty
 * value and a malformed one such as {@code yes} all read as not trusted. What opens the gate is
 * {@link Boolean#parseBoolean}, which is case-insensitive: {@code true}, {@code TRUE} and {@code True} all enable
 * trusted mode, and nothing else does.
 *
 * @author Ivica Cardic
 */
final class TaskRunnerTrustedMode {

    private static final String TRUSTED_ENABLED = "trusted-enabled";

    private TaskRunnerTrustedMode() {
    }

    static boolean isEnabled(ApplicationProperties applicationProperties, String type) {
        ApplicationProperties.Script.Runner runner = applicationProperties.getScript()
            .getRunners()
            .get(type);

        if (runner == null) {
            return false;
        }

        Map<String, String> properties = runner.getProperties();

        // A YAML "properties:" key with an empty body binds null, so the map is not guaranteed present even when the
        // runner entry is. Failing closed here rather than throwing keeps a half-written configuration from being the
        // one way trusted mode turns itself on.
        if (properties == null) {
            return false;
        }

        return Boolean.parseBoolean(properties.get(TRUSTED_ENABLED));
    }

    /**
     * The fully qualified configuration key an operator would set, for use in an error an operator has to act on.
     */
    static String getPropertyName(String type) {
        return "bytechef.script.runners.%s.properties.%s".formatted(type, TRUSTED_ENABLED);
    }
}

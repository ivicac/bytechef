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

/**
 * Reads the operator's per-runner {@code trusted-enabled} escape hatch.
 *
 * <p>
 * Two callers read the same setting for different reasons - {@link TaskRunnerRegistryImpl} to decide whether to warn at
 * startup, and {@link GraalVmTaskRunner} to decide whether to reject a request asking for trusted mode - and they must
 * never disagree, so the name of the setting lives here rather than in both. The fail-closed parse itself is
 * {@link TaskRunnerOperatorFlag}'s, shared with every other runner's escape hatch.
 *
 * @author Ivica Cardic
 */
final class TaskRunnerTrustedMode {

    private static final String TRUSTED_ENABLED = "trusted-enabled";

    private TaskRunnerTrustedMode() {
    }

    static boolean isEnabled(ApplicationProperties applicationProperties, String type) {
        return TaskRunnerOperatorFlag.isEnabled(applicationProperties, type, TRUSTED_ENABLED);
    }

    /**
     * The fully qualified configuration key an operator would set, for use in an error an operator has to act on.
     */
    static String getPropertyName(String type) {
        return TaskRunnerOperatorFlag.getPropertyName(type, TRUSTED_ENABLED);
    }
}

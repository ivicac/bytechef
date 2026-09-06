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

package com.bytechef.component.ai.agent.guardrails.util;

import com.bytechef.platform.ai.sensitivedata.MatchDeadline;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;

/**
 * Starts the one regex time budget the per-node guardrail utilities match under. The budget is the platform's
 * {@code DetectionBounds.DEFAULTS.timeout()} so a node child and the workspace floor answer "how long may one match
 * run" identically; the count-based budget this replaces was a second bound with its own failure type.
 *
 * @author Ivica Cardic
 */
public final class GuardrailMatchDeadline {

    private GuardrailMatchDeadline() {
    }

    public static MatchDeadline start() {
        return MatchDeadline.in(SensitiveDataRedactor.DetectionBounds.DEFAULTS.timeout());
    }
}

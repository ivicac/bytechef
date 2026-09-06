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

package com.bytechef.platform.ai.sensitivedata;

import java.util.List;

/**
 * Constructs the detectors the guardrail engine ships with, for callers that assemble a {@link SensitiveDataRedactor}
 * outside a Spring context — {@code AiGuardrails}' legacy constructor and unit tests. In a running application the same
 * two detectors are contributed as beans and injected instead; both detectors are stateless, so the two paths are
 * interchangeable.
 *
 * @author Ivica Cardic
 */
public final class SensitiveDataDetectors {

    private SensitiveDataDetectors() {
    }

    /**
     * Returns the built-in regex detectors.
     *
     * @return the PII and secret detectors
     */
    public static List<SensitiveDataDetector> builtIn() {
        return List.of(new PresidioRegexPiiDetector(), new RegexSecretDetector());
    }
}

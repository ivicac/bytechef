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

package com.bytechef.platform.ai.constant;

/**
 * @author Ivica Cardic
 */
public final class ToolSuspendConstants {

    public static final String GATED_TOOL_NAME = "__BYTECHEF_GATED_TOOL_NAME__";

    public static final String GATED_TOOL_INPUT = "__BYTECHEF_GATED_TOOL_INPUT__";

    /**
     * The fixed suspended-tool marker. The PII tokenization boundary exempts a tool result equal to it from redaction
     * so the suspend protocol's marker survives untouched.
     */
    public static final String SUSPENDED_SENTINEL = "__BYTECHEF_TOOL_SUSPENDED__";

    /**
     * {@code continueParameters} key holding the ids of the component rules that required this approval, as a
     * {@code List<Long>}. Present only when an administrative rule raised the request; the gate does not set it.
     */
    public static final String RULE_IDS = "__BYTECHEF_RULE_IDS__";

    /** {@code continueParameters} key holding the component of the tool a rule required approval for. */
    public static final String RULE_COMPONENT_NAME = "__BYTECHEF_RULE_COMPONENT_NAME__";

    /** {@code continueParameters} key holding the cluster element name of that tool. */
    public static final String RULE_TOOL_NAME = "__BYTECHEF_RULE_TOOL_NAME__";

    private ToolSuspendConstants() {
    }
}

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

package com.bytechef.platform.ai.guardrails;

/**
 * Where a guarded response's restored text is going, which decides whether restoration is a policy question at all.
 *
 * <p>
 * Not derivable from {@link GuardrailSurface} alone. {@code AI_AGENT} covers three actions sharing one advisor, and two
 * of them stream to a live human: a rule keyed on the surface string would make a realtime voice agent read
 * {@code [PII_EMAIL_ADDRESS_1_k3n9]} back to the caller who had just spoken the address. The distinction is consent and
 * visibility, not the identity of the recipient, so the call site that knows its own route out supplies it.
 * </p>
 *
 * @author Ivica Cardic
 */
public enum RestorationDestination {

    /**
     * The restored text goes back to the party who supplied the input -- a chat thread, the Copilot panel, or a canvas
     * agent's streamed tokens. Restoration is unconditional; the consolidation spec's rule holds as written.
     */
    CONVERSATION,

    /**
     * The restored text becomes a workflow task output that downstream nodes read. The receiving party is whatever the
     * workflow author wired next, which is not the party that supplied the value, so restoration is a workspace policy
     * decision.
     */
    WORKFLOW_OUTPUT
}

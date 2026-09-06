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

import org.springframework.core.Ordered;

/**
 * The chain positions of the two guardrail advisors, fixed here so they can never tie.
 *
 * <p>
 * Spring AI breaks an {@code Ordered} tie toward the advisor registered LAST ({@code DefaultAroundAdvisorChain} pushes
 * with {@code Deque#push} and then stable-sorts), which is the inverse of what a reader assumes. Before these constants
 * existed the per-node check advisor tied the workspace advisor at {@code HIGHEST_PRECEDENCE} and, being registered
 * later, wrapped it — so a node-level output check saw the caller's restored values and blocked responses that merely
 * echoed the caller's own input. Two distinct values make the position a property of the code rather than of
 * registration order.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class GuardrailAdvisorOrder {

    /**
     * The workspace floor ({@code AiGuardrailsAdvisor}, and the deferred stand-in Copilot registers): outermost, so it
     * sees the caller's original request before any other advisor rewrites it and restores its tokens as the last act
     * on the response.
     */
    public static final int WORKSPACE_FLOOR = Ordered.HIGHEST_PRECEDENCE;

    /**
     * The per-node {@code CheckForViolationsAdvisor}: exactly one inside the floor, so on the response it judges only
     * what the model contributed — the floor's tokens are invisible to it and are restored after it ran.
     */
    public static final int NODE_CHECK = Ordered.HIGHEST_PRECEDENCE + 1;

    private GuardrailAdvisorOrder() {
    }
}

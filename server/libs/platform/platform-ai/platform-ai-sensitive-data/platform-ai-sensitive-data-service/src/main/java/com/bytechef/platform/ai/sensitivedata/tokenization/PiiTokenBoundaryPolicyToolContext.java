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

package com.bytechef.platform.ai.sensitivedata.tokenization;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Carries a {@link PiiTokenBoundaryPolicy} across the tool-call boundary via Spring AI's {@link ToolContext} -- mirrors
 * {@link PiiTokenSessionToolContext}, which carries the {@link PiiTokenSession} it is always resolved alongside. Kept
 * as its own key rather than folded into the session itself: {@link PiiTokenSession} is pure token-to-value state with
 * no notion of policy, and a second carrier lets a caller that has not been updated to resolve a policy keep working
 * unchanged (see {@link PiiTokenBoundaryPolicy#DEFAULT}).
 *
 * <p>
 * {@link #into(Map, PiiTokenBoundaryPolicy)} always returns a NEW map carrying every entry of {@code context} plus the
 * policy under {@link #KEY} -- it never mutates {@code context} in place and never drops an entry, for the same reason
 * {@link PiiTokenSessionToolContext#into} does not: {@code AgentToolInvocationContext} and the session itself live in
 * this same map.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class PiiTokenBoundaryPolicyToolContext {

    public static final String KEY = "bytechef.pii-token-boundary-policy";

    private PiiTokenBoundaryPolicyToolContext() {
    }

    /**
     * Returns the {@link PiiTokenBoundaryPolicy} carried on {@code toolContext} under {@link #KEY}, or {@code null}
     * when {@code toolContext} is {@code null}, carries nothing under that key, or carries a value of the wrong type.
     *
     * @param toolContext the tool context to read, or {@code null}
     * @return the carried policy, or {@code null}
     */
    public static @Nullable PiiTokenBoundaryPolicy from(@Nullable ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }

        Object value = toolContext.getContext()
            .get(KEY);

        return value instanceof PiiTokenBoundaryPolicy piiTokenBoundaryPolicy ? piiTokenBoundaryPolicy : null;
    }

    /**
     * Returns a new map containing every entry of {@code context} plus {@code policy} under {@link #KEY}.
     * {@code context} itself is left untouched.
     *
     * @param context the existing tool context entries to preserve
     * @param policy  the policy to carry
     * @return a new, merged map
     */
    public static Map<String, Object> into(Map<String, Object> context, PiiTokenBoundaryPolicy policy) {
        Map<String, Object> merged = new HashMap<>(context);

        merged.put(KEY, policy);

        return merged;
    }
}

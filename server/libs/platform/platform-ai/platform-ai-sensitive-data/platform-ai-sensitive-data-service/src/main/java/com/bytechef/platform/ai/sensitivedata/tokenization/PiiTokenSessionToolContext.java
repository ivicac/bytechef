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
 * Carries a {@link PiiTokenSession} across the tool-call boundary via Spring AI's {@link ToolContext}, the channel
 * {@code AgentToolInvocationContext} already uses for the same hop. Tool calls run on worker threads that do not
 * inherit {@code EnvironmentContext}, {@code TenantContext} or the Spring {@code SecurityContext} -- a session held in
 * a {@code ThreadLocal} would be invisible there, which is why {@code RehydrateContextToolCallback} exists for those
 * three and this class exists for the token session.
 *
 * <p>
 * {@link #into(Map, PiiTokenSession)} always returns a NEW map that carries every entry of {@code context} plus the
 * session under {@link #KEY} -- it never mutates {@code context} in place and never drops an entry. This matters beyond
 * tidiness: {@code AgentToolInvocationContext} (workspace/user/environment/tenant/authentication) lives in the very
 * same {@code ToolContext} map. A caller that replaced the map instead of merging into it would silently strip that
 * context off every tool call sharing the request, breaking security-context rehydration on the worker thread -- a
 * failure that would surface far from here, as an authorization error inside some unrelated tool.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class PiiTokenSessionToolContext {

    public static final String KEY = "bytechef.pii-token-session";

    private PiiTokenSessionToolContext() {
    }

    /**
     * Returns the {@link PiiTokenSession} carried on {@code toolContext} under {@link #KEY}, or {@code null} when
     * {@code toolContext} is {@code null}, carries nothing under that key, or carries a value of the wrong type.
     *
     * @param toolContext the tool context to read, or {@code null}
     * @return the carried session, or {@code null}
     */
    public static @Nullable PiiTokenSession from(@Nullable ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }

        Object value = toolContext.getContext()
            .get(KEY);

        return value instanceof PiiTokenSession piiTokenSession ? piiTokenSession : null;
    }

    /**
     * Returns a new map containing every entry of {@code context} plus {@code session} under {@link #KEY}.
     * {@code context} itself is left untouched.
     *
     * @param context the existing tool context entries to preserve
     * @param session the session to carry
     * @return a new, merged map
     */
    public static Map<String, Object> into(Map<String, Object> context, PiiTokenSession session) {
        Map<String, Object> merged = new HashMap<>(context);

        merged.put(KEY, session);

        return merged;
    }

    /**
     * Returns a map containing every entry of {@code context} EXCEPT the session under {@link #KEY}, so the session
     * does not travel any further than the component that needs it. {@code context} itself is left untouched, and
     * {@code AgentToolInvocationContext}'s entries -- which share this map -- are preserved, exactly as
     * {@link #into(Map, PiiTokenSession)} preserves them.
     *
     * <p>
     * The session is a live two-way map from token to original value. Only {@code PiiTokenBoundaryToolCallingManager}
     * needs it, and only for the two moments either side of a tool executing: restoring tokens in the call's arguments
     * beforehand, and tokenizing the result afterwards. Everything a tool callback itself receives goes to code that
     * may not be ByteChef's -- a Script action exposed as an agent tool runs user-authored Java/JavaScript/Python/Ruby
     * with the {@code ToolContext} in reach, and reading the session from there would hand that code a de-tokenizing
     * oracle for every value the guardrail had protected in this request. Stripping the key before delegating keeps the
     * reachable surface to the boundary itself.
     * </p>
     *
     * @param context the existing tool context entries to preserve
     * @return a new map without the session, or {@code context} unchanged when it carries none
     */
    public static Map<String, Object> without(Map<String, Object> context) {
        if (!context.containsKey(KEY)) {
            return context;
        }

        Map<String, Object> stripped = new HashMap<>(context);

        stripped.remove(KEY);

        return stripped;
    }
}

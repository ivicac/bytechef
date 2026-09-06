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

import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;
import com.bytechef.platform.ai.sensitivedata.SensitiveKind;
import java.util.Set;

/**
 * The effective policy {@link PiiTokenBoundaryToolCallingManager} applies at the tool-call boundary: which
 * {@link SensitiveKind}s to tokenize/redact in a tool result, and the minimum confidence a candidate span must meet.
 * Resolved from the workspace's {@code AiGuardrailsWorkspaceSettings} (EE) and carried across the tool-call boundary
 * via {@link PiiTokenBoundaryPolicyToolContext}, the same {@code ToolContext} channel {@link PiiTokenSession} already
 * uses -- both are resolved together, from the same workspace, at the same call site
 * ({@code AiGuardrailsAdvisor#withSessionInToolContext}).
 *
 * @param kinds         the sensitive-data kinds this workspace has enabled
 * @param minConfidence the minimum confidence, inclusive, a candidate span must meet to be tokenized/redacted
 * @author Ivica Cardic
 */
public record PiiTokenBoundaryPolicy(Set<SensitiveKind> kinds, double minConfidence) {

    /**
     * Defensively copies {@code kinds} into an immutable set -- callers must not be able to mutate this policy's
     * enabled kinds after construction by continuing to hold and mutate the {@link Set} they passed in, and this
     * record's generated accessor must not hand back a reference the caller could mutate either.
     */
    public PiiTokenBoundaryPolicy {
        kinds = Set.copyOf(kinds);
    }

    /**
     * {@link PiiTokenBoundaryToolCallingManager}'s behaviour before this class existed -- both PII and SECRET, at
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE} -- and what it falls back to when a session is present on
     * the tool context but no policy was wired alongside it. That fallback keeps every caller that has not yet been
     * updated to carry a policy (this module's own unit tests, and any future call site) behaving exactly as before,
     * rather than silently going inert.
     */
    public static final PiiTokenBoundaryPolicy DEFAULT = new PiiTokenBoundaryPolicy(
        Set.of(SensitiveKind.PII, SensitiveKind.SECRET), SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE);
}

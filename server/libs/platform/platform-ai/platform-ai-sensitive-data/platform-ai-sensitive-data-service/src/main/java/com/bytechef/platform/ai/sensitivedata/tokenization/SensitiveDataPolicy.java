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
 * A resolved kinds-and-threshold policy: which {@link SensitiveKind}s to tokenize or redact, the minimum confidence a
 * candidate span must meet, and -- at the tool-call boundary only -- whether a tool call's OUTBOUND arguments may be
 * restored to real values before it runs. Resolved from the workspace's {@code AiGuardrailsWorkspaceSettings} (EE).
 *
 * <p>
 * <b>Two surfaces consume this, which is why its name is surface-neutral.</b>
 * {@link PiiTokenBoundaryToolCallingManager} applies it at the tool-call boundary, receiving it across that boundary
 * via {@link SensitiveDataPolicyToolContext} -- the same {@code ToolContext} channel {@link PiiTokenSession} already
 * uses, both resolved together, from the same workspace, at the same call site
 * ({@code AiGuardrailsAdvisor#withSessionInToolContext}). The EE MCP outbound path
 * ({@code AiGuardrails#resolveMcpOutboundPolicy} to {@code RedactingToolCallback}) applies it to a tool result leaving
 * an MCP server for an external agent. That surface only ever redacts: it never tokenizes and never restores, so it
 * reads {@code kinds} and {@code minConfidence} and nothing else. The record was called {@code PiiTokenBoundaryPolicy}
 * until 2026-09-06, when it was renamed here because a {@code PiiToken*} name on a redaction-only path is exactly the
 * sort that goes stale and misleads a later reader; the naming debt was recorded deliberately in
 * {@code docs/superpowers/specs/2026-09-02-mcp-outbound-guardrails-design.md} rather than absorbed.
 * </p>
 *
 * <p>
 * <b>{@code restoreOutboundArguments} is the one component that is NOT surface-neutral.</b> It is meaningful only at
 * the tool-call boundary. Both MCP resolvers pass a hardcoded {@code true} for it that no MCP code path ever reads --
 * harmless, but the reason it is safe is that {@code RedactingToolCallback} has no outbound direction to gate, not that
 * {@code true} is the right answer there. Splitting it onto its own type is the real fix and is deliberately not done
 * here: this was a rename, and the split changes the tool boundary's {@code DEFAULT} fallback semantics, which wants
 * its own decision.
 * </p>
 *
 * <p>
 * This record stays in the {@code ...sensitivedata.tokenization} package despite the surface-neutral name, because
 * {@code restoreOutboundArguments} genuinely belongs to the tokenization boundary. Moving it up to
 * {@code ...sensitivedata} follows the split above, not this rename.
 * </p>
 *
 * <p>
 * <b>{@code kinds}/{@code minConfidence} and {@code restoreOutboundArguments} govern two distinct directions, and this
 * design has already confused them once.</b> {@code kinds}/{@code minConfidence} govern only the INBOUND direction --
 * what is tokenized/redacted in a tool RESULT on its way back to the model. {@code restoreOutboundArguments} governs
 * only the OUTBOUND direction -- whether a tool call's {@code arguments}, minted as tokens by the model, are restored
 * to real values before the delegate runs the tool. A workspace can (and by default does) restore outbound while its
 * inbound {@code kinds} still tokenize a fresh tool result; the two toggles are independent and must stay that way.
 * </p>
 *
 * @param kinds                    the sensitive-data kinds this workspace has enabled, for the INBOUND direction (a
 *                                 tool RESULT reaching the model)
 * @param minConfidence            the minimum confidence, inclusive, a candidate span must meet to be
 *                                 tokenized/redacted, for the same INBOUND direction
 * @param restoreOutboundArguments whether a tool call's arguments may be restored to real values before the delegate
 *                                 runs -- the OUTBOUND direction, distinct from {@code kinds}/{@code minConfidence},
 *                                 which govern only what is tokenized in a tool RESULT on the way back to the model
 * @author Ivica Cardic
 */
public record SensitiveDataPolicy(Set<SensitiveKind> kinds, double minConfidence, boolean restoreOutboundArguments) {

    /**
     * Defensively copies {@code kinds} into an immutable set -- callers must not be able to mutate this policy's
     * enabled kinds after construction by continuing to hold and mutate the {@link Set} they passed in, and this
     * record's generated accessor must not hand back a reference the caller could mutate either.
     */
    public SensitiveDataPolicy {
        kinds = Set.copyOf(kinds);
    }

    /**
     * {@link PiiTokenBoundaryToolCallingManager}'s behaviour before this class existed -- both PII and SECRET, at
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE}, outbound arguments always restored -- and what it falls
     * back to when a session is present on the tool context but no policy was wired alongside it. That fallback keeps
     * every caller that has not yet been updated to carry a policy (this module's own unit tests, and any future call
     * site) behaving exactly as before, rather than silently going inert. {@code restoreOutboundArguments} is {@code
     * true} for the same reason: an un-updated caller must keep restoring tool-call arguments, since a caller that
     * silently stopped restoring them would hand raw PII tokens to a live integration instead of the real value it
     * expects.
     */
    public static final SensitiveDataPolicy DEFAULT = new SensitiveDataPolicy(
        Set.of(SensitiveKind.PII, SensitiveKind.SECRET), SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE, true);
}

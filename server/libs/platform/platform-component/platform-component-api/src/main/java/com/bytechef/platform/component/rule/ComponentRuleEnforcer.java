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

package com.bytechef.platform.component.rule;

import com.bytechef.platform.constant.PlatformType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Extension point for administrative, conditional governance of a single AI agent tool call. CE ships no
 * implementation, so every call is a no-op and behaviour is unchanged; EE ships a persistence-backed implementation
 * that evaluates admin-authored conditions against the call's actual input (and, after the fact, its output).
 *
 * <p>
 * This governs tool calls, not workflow action executions: the risky calls are the ones a model chooses at runtime. The
 * enforcement point is the tool callback wrapper, not the action-execution chokepoint, because a tool produced
 * dynamically by a provider element never passes through the latter.
 * </p>
 *
 * @author Ivica Cardic
 */
public interface ComponentRuleEnforcer {

    /**
     * One tool invocation, reduced to the facts a rule can be written against.
     *
     * @param componentName   the component the tool element belongs to
     * @param toolName        the cluster element name — the name a rule is keyed on
     * @param toolCallName    the name the model invoked, which differs from {@code toolName} for a tool produced by a
     *                        provider element; a rule on the provider element governs all of its tools
     * @param inputParameters the tool's resolved input for this call
     * @param connectionId    the id of the first connection wired to this call, or {@code null} when there is none
     * @param jobId           the id of the job this call runs under, or {@code null} when there is none
     * @param taskExecutionId the id of the task execution this call belongs to, or {@code null}
     * @param approvedBy      the verified reviewer of a human-approved re-execution, or {@code null} on a first
     *                        attempt. When set, a matching approval rule is already satisfied; block and tag rules are
     *                        still evaluated.
     * @param jobPrincipalId  the job principal this call runs under — a project-deployment id under
     *                        {@link PlatformType#AUTOMATION} — or {@code null} when there is none. An implementation
     *                        resolves the governing workspace from this; the wrapper does not, because it lives in a CE
     *                        module and the resolution is an EE concern.
     * @param platformType    the platform this call runs under, or {@code null}. Only {@link PlatformType#AUTOMATION}
     *                        carries a workspace.
     */
    @SuppressFBWarnings("EI")
    record ToolCall(
        String componentName, String toolName, String toolCallName, Map<String, ?> inputParameters,
        @Nullable Long connectionId, @Nullable Long jobId, @Nullable Long taskExecutionId,
        @Nullable String approvedBy, @Nullable Long jobPrincipalId, @Nullable PlatformType platformType) {
    }

    /**
     * What the caller must do with the call. Precedence among matching rules is fixed: any block wins, else any
     * approval wins, else tags are recorded and the call proceeds.
     */
    sealed interface Decision permits Decision.Allow, Decision.Block, Decision.RequireApproval {

        /**
         * Run the tool.
         */
        record Allow() implements Decision {
        }

        /**
         * Refuse the tool, reporting {@code reason} to the model.
         */
        record Block(String reason) implements Decision {
        }

        /**
         * Pause for a human. {@code ruleIds} carries every matching approval rule, so one decision satisfies all of
         * them.
         */
        @SuppressFBWarnings("EI")
        record RequireApproval(List<Long> ruleIds, String title, String description, Instant expiresAt)
            implements Decision {
        }
    }

    /**
     * Runs before the tool executes. Never throws: an enforcement failure must not fail an agent turn.
     */
    Decision checkBeforeCall(ToolCall toolCall);

    /**
     * Runs after the tool returns, with its output. Never throws: the tool has already run and its side effects already
     * happened.
     */
    void recordAfterCall(ToolCall toolCall, @Nullable Object output);

    /**
     * Records a human's decision on an approval this enforcer required. Never throws.
     */
    void recordApprovalResolution(
        List<Long> ruleIds, ToolCall toolCall, boolean approved, @Nullable String approvedBy);
}

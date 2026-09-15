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

package com.bytechef.component.ai.llm.tool;

import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.tool.execution.ToolExecutionEvent;
import com.bytechef.platform.tool.execution.ToolExecutionKind;
import com.bytechef.platform.tool.execution.ToolExecutionSurface;
import com.bytechef.platform.tool.execution.ToolInvocationWorkspaceResolver;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the {@link ToolExecutionSurface#AI_AGENT} audit events a workflow agent emits — a rule block, a raised
 * approval gate, a gate resolution — scoped to the run they belong to: its job, its environment and, when a
 * {@link ToolInvocationWorkspaceResolver} is present, its workspace. A run without an environment (an editor test run)
 * or without a resolvable workspace (an embedded run, CE) leaves that field unset. A resolver that throws is treated as
 * no workspace: an audit event must never stop the tool it describes from running.
 *
 * @author Ivica Cardic
 */
public final class AiAgentToolExecutionEvents {

    private static final Logger log = LoggerFactory.getLogger(AiAgentToolExecutionEvents.class);

    private AiAgentToolExecutionEvents() {
    }

    public static ToolExecutionEvent.Builder builder(
        String toolName, ActionContextAware actionContext,
        @Nullable ToolInvocationWorkspaceResolver toolInvocationWorkspaceResolver) {

        return ToolExecutionEvent.builder(ToolExecutionSurface.AI_AGENT, ToolExecutionKind.COMPONENT, toolName)
            .jobId(actionContext.getJobId())
            .environment(toEnvironment(actionContext.getEnvironmentId()))
            .workspaceId(resolveWorkspaceId(actionContext, toolInvocationWorkspaceResolver));
    }

    private static @Nullable Long resolveWorkspaceId(
        ActionContextAware actionContext, @Nullable ToolInvocationWorkspaceResolver toolInvocationWorkspaceResolver) {

        if (toolInvocationWorkspaceResolver == null) {
            return null;
        }

        try {
            return toolInvocationWorkspaceResolver.resolve(
                actionContext.getPlatformType(), actionContext.getJobPrincipalId());
        } catch (RuntimeException runtimeException) {
            log.warn("Failed to resolve the workspace of an AI agent tool execution event", runtimeException);

            return null;
        }
    }

    private static @Nullable Integer toEnvironment(@Nullable Long environmentId) {
        return environmentId == null ? null : Math.toIntExact(environmentId);
    }
}

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

package com.bytechef.automation.ai.tool;

import com.bytechef.ai.copilot.tool.context.AgentToolInvocationContext;
import com.bytechef.automation.configuration.domain.Workspace;
import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.platform.configuration.domain.Environment;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

/**
 * @author Ivica Cardic
 */
public record AutomationToolInvocationContext(
    @Nullable Long workspaceId, @Nullable Long environmentId, @Nullable Long userId, @Nullable Short sourceOrdinal,
    @Nullable String lastUserPrompt, @Nullable String threadId) {

    public static final String TOOL_CONTEXT_WORKSPACE_ID_KEY = "bytechef.automationTool.workspaceId";
    public static final String TOOL_CONTEXT_ENVIRONMENT_ID_KEY = "bytechef.automationTool.environmentId";
    public static final String TOOL_CONTEXT_USER_ID_KEY = "bytechef.automationTool.userId";
    public static final String TOOL_CONTEXT_SOURCE_ORDINAL_KEY = "bytechef.automationTool.sourceOrdinal";
    public static final String TOOL_CONTEXT_LAST_USER_PROMPT_KEY = "bytechef.automationTool.lastUserPrompt";
    public static final String TOOL_CONTEXT_THREAD_ID_KEY = "bytechef.automationTool.threadId";

    /**
     * CE-visible mirror of {@code com.bytechef.ee.ai.hub.util.Source.FILES.toAgentSourceOrdinal()} — the agent-source
     * ordinal persisted into {@code asset_file.generated_by_agent_source} for files created from the Files surface.
     */
    public static final short SOURCE_ORDINAL_FILES = 3;

    public static final String WORKSPACE_ID_DESCRIPTION =
        "The workspace ID. Optional inside a Copilot chat, which defaults to the chat's workspace; otherwise " +
            "required - call listWorkspaces to find it.";

    public AutomationToolInvocationContext(@Nullable Long workspaceId, @Nullable Long environmentId) {
        this(workspaceId, environmentId, null, null, null, null);
    }

    public static @Nullable AutomationToolInvocationContext fromToolContext(@Nullable ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }

        Map<String, Object> map = toolContext.getContext();

        if (map == null || map.isEmpty()) {
            return null;
        }

        Long workspaceId = asLong(map.get(TOOL_CONTEXT_WORKSPACE_ID_KEY));
        Long environmentId = asLong(map.get(TOOL_CONTEXT_ENVIRONMENT_ID_KEY));
        Long userId = asLong(map.get(TOOL_CONTEXT_USER_ID_KEY));
        Short sourceOrdinal = asShort(map.get(TOOL_CONTEXT_SOURCE_ORDINAL_KEY));
        String lastUserPrompt = asString(map.get(TOOL_CONTEXT_LAST_USER_PROMPT_KEY));
        String threadId = asString(map.get(TOOL_CONTEXT_THREAD_ID_KEY));

        if (workspaceId == null && environmentId == null && userId == null && sourceOrdinal == null
            && lastUserPrompt == null && threadId == null) {

            return null;
        }

        return new AutomationToolInvocationContext(
            workspaceId, environmentId, userId, sourceOrdinal, lastUserPrompt, threadId);
    }

    public static int resolveEnvironmentOrDefault(@Nullable AutomationToolInvocationContext context) {
        Long environmentId = context == null ? null : context.environmentId();

        if (environmentId == null) {
            return 0;
        }

        return environmentId.intValue();
    }

    /**
     * Returns the workspace a tool call targets: the explicitly requested workspace, otherwise the workspace carried in
     * the tool context (an in-editor Copilot chat carries one), otherwise {@code null}. A requested workspace must be
     * the tool context's workspace when the context carries one, and otherwise one of the caller's workspaces.
     *
     * @throws IllegalArgumentException if the requested workspace is not accessible to the caller
     */
    public static @Nullable Long resolveWorkspaceId(
        @Nullable Long requestedWorkspaceId, @Nullable ToolContext toolContext, WorkspaceFacade workspaceFacade) {

        AutomationToolInvocationContext context = fromToolContext(toolContext);

        Long contextWorkspaceId = context == null ? null : context.workspaceId();

        if (requestedWorkspaceId == null) {
            return contextWorkspaceId;
        }

        if (contextWorkspaceId != null) {
            if (!requestedWorkspaceId.equals(contextWorkspaceId)) {
                throw new IllegalArgumentException(
                    "Workspace " + requestedWorkspaceId + " is not the workspace " + contextWorkspaceId +
                        " this conversation is scoped to.");
            }

            return contextWorkspaceId;
        }

        List<Workspace> workspaces = workspaceFacade.getMyWorkspaces();

        boolean accessible = workspaces.stream()
            .anyMatch(workspace -> requestedWorkspaceId.equals(workspace.getId()));

        if (!accessible) {
            throw new IllegalArgumentException(
                "Workspace " + requestedWorkspaceId + " is not accessible to the caller.");
        }

        return requestedWorkspaceId;
    }

    /**
     * Returns the environment a tool call targets: the explicitly requested environment name, otherwise the environment
     * carried in the tool context, otherwise {@link Environment#DEVELOPMENT}.
     *
     * @throws IllegalArgumentException if the requested environment name is not a known environment
     */
    public static long resolveEnvironmentId(@Nullable String requestedEnvironment, @Nullable ToolContext toolContext) {
        if (requestedEnvironment != null && !requestedEnvironment.isBlank()) {
            Environment environment = Environment.valueOf(requestedEnvironment.toUpperCase(Locale.ROOT));

            return environment.ordinal();
        }

        AutomationToolInvocationContext context = fromToolContext(toolContext);

        Long contextEnvironmentId = context == null ? null : context.environmentId();

        if (contextEnvironmentId != null) {
            return contextEnvironmentId;
        }

        return Environment.DEVELOPMENT.ordinal();
    }

    public static Map<String, Object> toForwardedContext(
        @Nullable ToolContext toolContext, long workspaceId, long environmentId) {

        Map<String, Object> forwardedContext = new HashMap<>();

        if (toolContext != null) {
            forwardedContext.putAll(toolContext.getContext());
        }

        forwardedContext.put(TOOL_CONTEXT_WORKSPACE_ID_KEY, workspaceId);
        forwardedContext.put(TOOL_CONTEXT_ENVIRONMENT_ID_KEY, environmentId);
        forwardedContext.putAll(
            AgentToolInvocationContext.builder()
                .workspaceId(workspaceId)
                .environmentId(environmentId)
                .build()
                .toToolContext());

        return forwardedContext;
    }

    private static @Nullable Long asLong(@Nullable Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number numberValue) {
            return numberValue.longValue();
        }

        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Long.parseLong(stringValue);
            } catch (NumberFormatException exception) {
                return null;
            }
        }

        return null;
    }

    private static @Nullable Short asShort(@Nullable Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number numberValue) {
            return numberValue.shortValue();
        }

        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Short.parseShort(stringValue);
            } catch (NumberFormatException exception) {
                return null;
            }
        }

        return null;
    }

    private static @Nullable String asString(@Nullable Object value) {
        return value == null ? null : value.toString();
    }
}

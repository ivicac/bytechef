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

import com.bytechef.ai.agent.tool.ToolErrors;
import com.bytechef.ai.copilot.tool.ask.SubAgentPendingQuestionSignal;
import com.bytechef.ai.copilot.tool.util.WorkflowPersistCaptureUtils;
import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.automation.configuration.workflow.CallableWorkflowCheck;
import com.bytechef.automation.configuration.workflow.CallableWorkflowChecker;
import com.bytechef.commons.util.JsonUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;

/**
 * @author Ivica Cardic
 */
public class WorkspaceScopedSubAgentToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceScopedSubAgentToolCallback.class);

    private static final String INPUT_SCHEMA =
        """
            {
                "type": "object",
                "properties": {
                    "request": {
                        "type": "string",
                        "description": "The task for the specialist, plus any ids or decisions already resolved."
                    },
                    "workspaceId": {
                        "type": "integer",
                        "description": "Target workspace id. Optional inside a Copilot chat, which defaults to the chat's workspace; otherwise required - call listWorkspaces to find it."
                    },
                    "environment": {
                        "type": "string",
                        "description": "Target environment: DEVELOPMENT, STAGING, or PRODUCTION. Optional - defaults to the chat's environment, or DEVELOPMENT."
                    }
                },
                "required": ["request"]
            }""";

    private static final String APP_CALLABLE_INPUT_SCHEMA =
        """
            {
                "type": "object",
                "properties": {
                    "request": {
                        "type": "string",
                        "description": "The task for the specialist, plus any ids or decisions already resolved."
                    },
                    "workspaceId": {
                        "type": "integer",
                        "description": "Target workspace id. Optional inside a Copilot chat, which defaults to the chat's workspace; otherwise required - call listWorkspaces to find it."
                    },
                    "environment": {
                        "type": "string",
                        "description": "Target environment: DEVELOPMENT, STAGING, or PRODUCTION. Optional - defaults to the chat's environment, or DEVELOPMENT."
                    },
                    "appCallable": {
                        "type": "boolean",
                        "description": "Build or change the workflow so an app can call it: a workflow/v1/newWorkflowCall trigger with an inputSchema and a closing workflow/v1/responseToWorkflowCall task. Only DRAFT workflows are edited, and the result says whether every saved workflow is app-callable."
                    }
                },
                "required": ["request"]
            }""";

    private static final String APP_CALLABLE_INSTRUCTION =
        "\n\nApp-callable requirements: the workflow's trigger is workflow/v1/newWorkflowCall with an inputSchema " +
            "parameter that is a string holding a JSON Schema that describes every value the caller supplies. The " +
            "workflow ends with a workflow/v1/responseToWorkflowCall task carrying an outputSchema parameter that " +
            "is a string holding a JSON Schema of the result. Edit only workflows of the project's DRAFT version.";

    private final ToolCallback delegate;
    private final @Nullable CallableWorkflowChecker callableWorkflowChecker;
    private final WorkspaceFacade workspaceFacade;

    public WorkspaceScopedSubAgentToolCallback(ToolCallback delegate, WorkspaceFacade workspaceFacade) {
        this(delegate, workspaceFacade, null);
    }

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public WorkspaceScopedSubAgentToolCallback(
        ToolCallback delegate, WorkspaceFacade workspaceFacade,
        @Nullable CallableWorkflowChecker callableWorkflowChecker) {

        this.delegate = delegate;
        this.callableWorkflowChecker = callableWorkflowChecker;
        this.workspaceFacade = workspaceFacade;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        ToolDefinition delegateToolDefinition = delegate.getToolDefinition();

        String description = delegateToolDefinition.description() +
            " Supply workspaceId outside a Copilot chat (call listWorkspaces to find it). Supply environment" +
            " (DEVELOPMENT, STAGING, or PRODUCTION) to target another environment.";

        if (callableWorkflowChecker != null) {
            description += " Set appCallable to true to build a workflow an app can call.";
        }

        return ToolDefinition.builder()
            .name(delegateToolDefinition.name())
            .description(description)
            .inputSchema(callableWorkflowChecker == null ? INPUT_SCHEMA : APP_CALLABLE_INPUT_SCHEMA)
            .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        try {
            WorkspaceScopedInput input = JsonUtils.read(toolInput, WorkspaceScopedInput.class);

            String request = input.request();

            if (request == null || request.isBlank()) {
                return ToolErrors.toolError("request is required and must not be blank");
            }

            CallableWorkflowChecker appCallableWorkflowChecker = Boolean.TRUE.equals(input.appCallable())
                ? callableWorkflowChecker : null;

            Long workspaceId;

            try {
                workspaceId = AutomationToolInvocationContext.resolveWorkspaceId(
                    input.workspaceId(), toolContext, workspaceFacade);
            } catch (IllegalArgumentException exception) {
                return ToolErrors.toolError(exception.getMessage());
            }

            if (workspaceId == null) {
                return ToolErrors.toolError("workspaceId is required - call listWorkspaces to find the workspace id.");
            }

            long environmentId;

            try {
                environmentId = AutomationToolInvocationContext.resolveEnvironmentId(
                    input.environment(), toolContext);
            } catch (IllegalArgumentException exception) {
                return ToolErrors.toolError(
                    "Unknown environment '" + input.environment() + "'. Supported: DEVELOPMENT, STAGING, PRODUCTION");
            }

            Map<String, Object> forwardedContext = AutomationToolInvocationContext.toForwardedContext(
                toolContext, workspaceId, environmentId);

            if (appCallableWorkflowChecker != null) {
                forwardedContext = SubAgentPendingQuestionSignal.withSignal(
                    WorkflowPersistCaptureUtils.withCaptureHolder(forwardedContext));

                forwardedContext.put(AppCallableDraftGuard.APP_CALLABLE_KEY, true);
            }

            String delegateInput = JsonUtils.write(
                Map.of("request", appCallableWorkflowChecker == null ? request : request + APP_CALLABLE_INSTRUCTION));

            String result = delegate.call(delegateInput, new ToolContext(forwardedContext));

            if (appCallableWorkflowChecker == null || SubAgentPendingQuestionSignal.isPending(forwardedContext)) {
                return result;
            }

            return checkAppCallable(
                result, WorkflowPersistCaptureUtils.getCapturedWorkflowIds(forwardedContext),
                appCallableWorkflowChecker);
        } catch (JacksonException exception) {
            return ToolErrors.toolError("Invalid tool input: " + exception.getMessage());
        } catch (RuntimeException exception) {
            ToolDefinition toolDefinition = delegate.getToolDefinition();

            return ToolErrors.runtimeFailure(WorkspaceScopedSubAgentToolCallback.class, toolDefinition.name(),
                exception);
        }
    }

    private static String checkAppCallable(
        String result, List<String> workflowIds, CallableWorkflowChecker appCallableWorkflowChecker) {

        if (workflowIds.isEmpty()) {
            return result;
        }

        List<String> failures = new ArrayList<>();

        for (String workflowId : workflowIds) {
            CallableWorkflowCheck callableWorkflowCheck;

            try {
                callableWorkflowCheck = appCallableWorkflowChecker.check(workflowId);
            } catch (RuntimeException exception) {
                log.warn("Checking whether workflow {} is app-callable failed", workflowId, exception);

                failures.add("workflowId " + workflowId + ": could not be checked");

                continue;
            }

            if (!callableWorkflowCheck.callable()) {
                failures.add("workflowId " + workflowId + ": " + String.join("; ", callableWorkflowCheck.problems()));
            }
        }

        if (failures.isEmpty()) {
            return result + "\n\nApp-callable: " + String.join(", ", workflowIds) +
                ". Publish the project, then call deployProject.";
        }

        return ToolErrors.toolError(
            "The workflow was saved but an app cannot call it. " + String.join(" | ", failures) +
                ". Call buildWorkflow again with appCallable true, naming the workflowId, to fix it.");
    }

    public record WorkspaceScopedInput(
        @Nullable String request, @Nullable Long workspaceId, @Nullable String environment,
        @Nullable Boolean appCallable) {
    }
}

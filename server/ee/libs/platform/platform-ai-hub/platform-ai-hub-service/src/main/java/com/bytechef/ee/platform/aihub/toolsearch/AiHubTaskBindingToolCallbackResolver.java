/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.aihub.toolsearch;

import com.bytechef.ee.platform.aihub.agent.AiHubSpringAIAgent.TaskToolBindingResolver;
import com.bytechef.ee.platform.aihub.task.AiHubTask;
import com.bytechef.ee.platform.aihub.task.AiHubTaskService;
import com.bytechef.ee.platform.aihub.task.AiHubTaskToolBinding;
import com.bytechef.ee.platform.aihub.task.AiHubTaskToolFacade;
import com.bytechef.ee.platform.aihub.tool.AiHubToolInvocationContext;
import com.bytechef.ee.platform.aihub.util.ToolNameNormalizer;
import com.bytechef.platform.component.domain.ClusterElementDefinition;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.component.util.JsonSchemaGeneratorUtils;
import com.bytechef.platform.connection.service.ConnectionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * Per-request bridge between the persisted {@link AiHubTaskToolBinding}s and Spring AI's tool callback list. Looks up
 * the task by thread id, fetches its attached tools, and synthesizes one {@link ClusterElementToolCallback} per binding
 * — with the binding's pinned connection and pre-set parameters baked in so each invocation runs against exactly what
 * the user attached.
 *
 * <p>
 * Defensive in three ways:
 * </p>
 * <ul>
 * <li>Returns empty list when the workspace context lacks a thread id (no task to resolve against).</li>
 * <li>Returns empty list when the task row isn't found (race or stale id).</li>
 * <li>Skips individual bindings whose cluster element no longer exists in the catalog (component upgrade removed the
 * action) — log + continue so the rest still register.</li>
 * </ul>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class AiHubTaskBindingToolCallbackResolver implements TaskToolBindingResolver {

    private static final Logger log =
        LoggerFactory.getLogger(AiHubTaskBindingToolCallbackResolver.class);

    private final AiHubTaskService taskService;
    private final AiHubTaskToolFacade taskToolFacade;
    private final ClusterElementDefinitionService clusterElementDefinitionService;
    private final ConnectionService connectionService;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public AiHubTaskBindingToolCallbackResolver(
        AiHubTaskService taskService,
        AiHubTaskToolFacade taskToolFacade,
        ClusterElementDefinitionService clusterElementDefinitionService, ConnectionService connectionService) {

        this.taskService = taskService;
        this.taskToolFacade = taskToolFacade;
        this.clusterElementDefinitionService = clusterElementDefinitionService;
        this.connectionService = connectionService;
    }

    @Override
    public List<ToolCallback> resolve(AiHubToolInvocationContext invocationContext) {
        if (invocationContext == null || invocationContext.threadId() == null) {
            return List.of();
        }

        Optional<AiHubTask> task =
            taskService.findByThreadId(invocationContext.threadId());

        if (task.isEmpty()) {
            return List.of();
        }

        List<AiHubTaskToolBinding> bindings = taskToolFacade.listTaskTools(
            task.get()
                .getId());

        if (bindings.isEmpty()) {
            return List.of();
        }

        List<ToolCallback> callbacks = new ArrayList<>(bindings.size());

        for (AiHubTaskToolBinding binding : bindings) {
            ToolCallback callback = bindingToCallback(binding);

            if (callback != null) {
                callbacks.add(callback);
            }
        }

        return callbacks;
    }

    /**
     * Builds one callback per binding. Returns null and logs when the cluster element can no longer be resolved
     * (component upgraded / action removed) so the agent still gets the rest of the task's tools instead of failing the
     * whole turn.
     */
    private ToolCallback bindingToCallback(AiHubTaskToolBinding binding) {
        ClusterElementDefinition definition;

        try {
            definition = clusterElementDefinitionService.getClusterElementDefinition(
                binding.componentName(), binding.componentVersion(), binding.clusterElementName());
        } catch (RuntimeException exception) {
            // Most likely cause: component author removed or renamed the action between attach time and now.
            // Surface as a WARN so ops can correlate with the task; the user-facing remediation is
            // to detach the dead binding via removeTaskTool. v2 follow-up could auto-detach here.
            log.warn(
                "Skipping task tool binding {} — cluster element {}/{} v{} no longer in catalog",
                binding.taskToolId(), binding.componentName(), binding.clusterElementName(),
                binding.componentVersion(), exception);

            return null;
        }

        String toolName = ToolNameNormalizer.toToolName(binding.componentName(), binding.clusterElementName());

        // Description for the LLM tool definition. Mirrors the search-discovery path's formatting so the
        // chat model sees the same shape regardless of whether the tool came from a search hit or a
        // pre-attachment.
        String description = formatDescription(definition);

        String inputSchema = JsonSchemaGeneratorUtils.generateInputSchema(definition.getProperties());

        return new ClusterElementToolCallback(
            toolName, description, inputSchema, binding.componentName(), binding.componentVersion(),
            binding.clusterElementName(), clusterElementDefinitionService, connectionService,
            binding.connectionId(), binding.parameters());
    }

    private static String formatDescription(ClusterElementDefinition definition) {
        String description = definition.getDescription();
        String title = definition.getTitle();

        if (description != null && !description.isBlank()) {
            return title != null && !title.isBlank() ? title + ": " + description : description;
        }

        return title != null && !title.isBlank() ? title : "(no description)";
    }
}

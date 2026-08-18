/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.domain.WorkflowTask;
import com.bytechef.ee.embedded.configuration.dto.ConnectedUserWorkflowTemplateDTO;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.definition.WorkflowNodeType;
import com.bytechef.platform.workflow.task.dispatcher.domain.TaskDispatcherDefinition;
import com.bytechef.platform.workflow.task.dispatcher.service.TaskDispatcherDefinitionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves the distinct {@link ComponentDefinition}s used by a workflow's triggers and tasks. Shared by
 * {@link AutomationWorkflowProjectFacadeImpl} (catalog project/workflow listings) and
 * {@link ConnectedUserProjectFacadeImpl} (a connected user's own workflow listing, including automation-bridge
 * reference rows).
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class WorkflowComponentResolver {

    /**
     * A workflow with no trigger at all still runs -- it is started by hand -- so the listing names that rather than
     * showing a workflow with no trigger whatsoever.
     */
    private static final String MANUAL_COMPONENT_NAME = "manual";

    private final ComponentDefinitionService componentDefinitionService;
    private final TaskDispatcherDefinitionService taskDispatcherDefinitionService;

    @SuppressFBWarnings("EI")
    public WorkflowComponentResolver(
        ComponentDefinitionService componentDefinitionService,
        TaskDispatcherDefinitionService taskDispatcherDefinitionService) {

        this.componentDefinitionService = componentDefinitionService;
        this.taskDispatcherDefinitionService = taskDispatcherDefinitionService;
    }

    /**
     * Distinct components used by the workflow's triggers followed by its tasks, in first-seen order.
     */
    public List<ConnectedUserWorkflowTemplateDTO.Component> getComponents(Workflow workflow) {
        List<ConnectedUserWorkflowTemplateDTO.Component> triggerComponents = getTriggerComponents(workflow);
        List<ConnectedUserWorkflowTemplateDTO.Component> taskComponents = getTaskComponents(workflow);

        List<ConnectedUserWorkflowTemplateDTO.Component> components = new ArrayList<>(triggerComponents);

        for (ConnectedUserWorkflowTemplateDTO.Component taskComponent : taskComponents) {
            if (!components.contains(taskComponent)) {
                components.add(taskComponent);
            }
        }

        return components;
    }

    public List<ConnectedUserWorkflowTemplateDTO.Component> getTaskComponents(Workflow workflow) {
        Map<String, WorkflowNodeType> componentsByName = new LinkedHashMap<>();

        for (WorkflowTask workflowTask : workflow.getTasks(true)) {
            WorkflowNodeType workflowNodeType = WorkflowNodeType.ofType(workflowTask.getType());

            // Every task counts, operation or not: a task dispatcher (condition, loop, Call AI Agent) carries no
            // operation, and filtering on one here would drop exactly what resolveComponent's task-dispatcher tier
            // exists to name.
            componentsByName.putIfAbsent(workflowNodeType.name(), workflowNodeType);
        }

        return resolveComponents(componentsByName);
    }

    public List<ConnectedUserWorkflowTemplateDTO.Component> getTriggerComponents(Workflow workflow) {
        Map<String, WorkflowNodeType> componentsByName = new LinkedHashMap<>();

        for (WorkflowTrigger workflowTrigger : WorkflowTrigger.of(workflow)) {
            WorkflowNodeType workflowNodeType = WorkflowNodeType.ofType(workflowTrigger.getType());

            componentsByName.putIfAbsent(workflowNodeType.name(), workflowNodeType);
        }

        if (componentsByName.isEmpty()) {
            componentsByName.put(
                MANUAL_COMPONENT_NAME, new WorkflowNodeType(MANUAL_COMPONENT_NAME, 1, MANUAL_COMPONENT_NAME));
        }

        return resolveComponents(componentsByName);
    }

    private List<ConnectedUserWorkflowTemplateDTO.Component> resolveComponents(
        Map<String, WorkflowNodeType> componentsByName) {

        List<ConnectedUserWorkflowTemplateDTO.Component> components = new ArrayList<>();

        for (WorkflowNodeType workflowNodeType : componentsByName.values()) {
            components.add(resolveComponent(workflowNodeType));
        }

        return components;
    }

    private ConnectedUserWorkflowTemplateDTO.Component resolveComponent(WorkflowNodeType workflowNodeType) {
        Optional<ComponentDefinition> componentDefinitionOptional =
            componentDefinitionService.fetchComponentDefinition(workflowNodeType.name(), workflowNodeType.version());

        if (componentDefinitionOptional.isPresent()) {
            ComponentDefinition componentDefinition = componentDefinitionOptional.get();

            return new ConnectedUserWorkflowTemplateDTO.Component(
                componentDefinition.getName(), componentDefinition.getTitle(), componentDefinition.getIcon());
        }

        Optional<TaskDispatcherDefinition> taskDispatcherDefinitionOptional =
            taskDispatcherDefinitionService.fetchTaskDispatcherDefinition(
                workflowNodeType.name(), workflowNodeType.version());

        if (taskDispatcherDefinitionOptional.isPresent()) {
            TaskDispatcherDefinition taskDispatcherDefinition = taskDispatcherDefinitionOptional.get();

            return new ConnectedUserWorkflowTemplateDTO.Component(
                taskDispatcherDefinition.getName(), taskDispatcherDefinition.getTitle(),
                taskDispatcherDefinition.getIcon());
        }

        return new ConnectedUserWorkflowTemplateDTO.Component(
            workflowNodeType.name(), workflowNodeType.name(), null);
    }
}

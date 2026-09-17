/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.commons.util.CollectionUtils;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.domain.ComponentConnection;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.configuration.facade.ComponentConnectionFacade;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Every connection slot of a workflow: triggers, every task including nested ones, and cluster elements (added by the
 * cluster root connection factory). The same enumeration {@code ProjectDeploymentFacadeImpl} uses to check that
 * required connections are set.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class WorkflowConnectionSlots {

    private final ComponentConnectionFacade componentConnectionFacade;
    private final WorkflowService workflowService;

    @SuppressFBWarnings("EI")
    public WorkflowConnectionSlots(ComponentConnectionFacade componentConnectionFacade,
        WorkflowService workflowService) {

        this.componentConnectionFacade = componentConnectionFacade;
        this.workflowService = workflowService;
    }

    public List<ComponentConnection> getSlots(String workflowId) {
        Workflow workflow = workflowService.getWorkflow(workflowId);

        return CollectionUtils.concat(
            WorkflowTrigger.of(workflow)
                .stream()
                .flatMap(workflowTrigger -> CollectionUtils.stream(
                    componentConnectionFacade.getComponentConnections(workflowTrigger)))
                .toList(),
            workflow.getTasks(true)
                .stream()
                .flatMap(workflowTask -> CollectionUtils.stream(
                    componentConnectionFacade.getComponentConnections(workflowTask)))
                .toList());
    }
}

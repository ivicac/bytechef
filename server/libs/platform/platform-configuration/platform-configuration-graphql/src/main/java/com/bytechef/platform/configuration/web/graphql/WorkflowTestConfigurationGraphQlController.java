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

package com.bytechef.platform.configuration.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.platform.configuration.facade.WorkflowTestConfigurationFacade;
import com.bytechef.platform.security.web.authentication.PrincipalEnvironment;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * @author Ivica Cardic
 */
@Controller
@ConditionalOnCoordinator
class WorkflowTestConfigurationGraphQlController {

    private final WorkflowTestConfigurationFacade workflowTestConfigurationFacade;

    WorkflowTestConfigurationGraphQlController(WorkflowTestConfigurationFacade workflowTestConfigurationFacade) {
        this.workflowTestConfigurationFacade = workflowTestConfigurationFacade;
    }

    @MutationMapping
    @PreAuthorize("hasPermission(#connectionId, 'Connection', 'CONNECTION_USE')")
    public boolean saveClusterElementTestConfigurationConnection(
        @Argument String workflowId, @Argument String workflowNodeName, @Argument String clusterElementType,
        @Argument String clusterElementWorkflowNodeName, @Argument String workflowConnectionKey,
        @Argument long connectionId, @Argument long environmentId) {

        // The gate above answers "may this caller use this connection?", and answers it per environment already:
        // Connection registers a ResourceEnvironmentResolver, so hasResourceScope checks the role the caller holds in
        // the connection's OWN environment. It deliberately does not answer "may this caller edit this workflow in
        // the environment they named" -- the facade below does, with
        // hasWorkflowScopeInEnvironment(#workflowId, 'WORKFLOW_EDIT', #environmentId). Two questions, two layers.
        //
        // Resolving here as well as there is harmless: resolveEffectiveEnvironmentId returns its input for a session
        // principal and the principal's own environment for a confined one, so applying it twice lands on the same
        // value. See PrincipalEnvironment.
        long effectiveEnvironmentId = PrincipalEnvironment.resolveEffectiveEnvironmentId(environmentId);

        workflowTestConfigurationFacade.saveClusterElementTestConfigurationConnection(
            workflowId, workflowNodeName, clusterElementType, clusterElementWorkflowNodeName, workflowConnectionKey,
            connectionId, effectiveEnvironmentId);

        return true;
    }

    @MutationMapping
    @PreAuthorize("hasPermission(#connectionId, 'Connection', 'CONNECTION_USE')")
    public boolean saveWorkflowTestConfigurationConnection(
        @Argument String workflowId, @Argument String workflowNodeName, @Argument String workflowConnectionKey,
        @Argument long connectionId, @Argument long environmentId) {

        // Same two-layer split as saveClusterElementTestConfigurationConnection above, and for the same reason.
        long effectiveEnvironmentId = PrincipalEnvironment.resolveEffectiveEnvironmentId(environmentId);

        workflowTestConfigurationFacade.saveWorkflowTestConfigurationConnection(
            workflowId, workflowNodeName, workflowConnectionKey, connectionId, effectiveEnvironmentId);

        return true;
    }
}

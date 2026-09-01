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

package com.bytechef.platform.component.context;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ClusterElementContext;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.TriggerContext;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.datastream.ClusterElementResolverFunction;
import com.bytechef.platform.constant.PlatformType;
import org.jspecify.annotations.Nullable;

/**
 * @author Ivica Cardic
 */
public interface ContextFactory {

    ActionContext createActionContext(
        String componentName, int componentVersion, String actionName, @Nullable Long jobPrincipalId,
        @Nullable Long jobPrincipalWorkflowId, @Nullable Long jobId, @Nullable Long taskExecutionId,
        @Nullable String workflowId, @Nullable ComponentConnection componentConnection,
        @Nullable Long environmentId, @Nullable PlatformType type, boolean editorEnvironment);

    Context createContext(String componentName, @Nullable ComponentConnection componentConnection);

    ClusterElementContext createClusterElementContext(
        String componentName, int componentVersion, String clusterElementName,
        @Nullable ComponentConnection componentConnection, boolean editorEnvironment);

    /**
     * Creates a {@link ClusterElementContext} that carries the job principal the run acts for, so a cluster element
     * invoked outside an AI agent can still derive an owner. Without it such an element has no principal at all, and an
     * owner derived from nothing is "sees everything" rather than "sees nothing".
     *
     * <p>
     * Both values are nullable together: a run with no persisted job principal (an editor test run) passes null for
     * each and the resulting context behaves exactly like the overload without them.
     *
     * @param componentName       the name of the cluster element's component
     * @param componentVersion    the version of the component
     * @param clusterElementName  the name of the cluster element
     * @param jobPrincipalId      the job principal the run belongs to -- a project-deployment id under
     *                            {@link PlatformType#AUTOMATION}, an integration-instance id under
     *                            {@link PlatformType#EMBEDDED} -- or null when the run has none
     * @param componentConnection the connection for the cluster element, or null if none
     * @param type                the platform the job runs under, or null when unknown
     * @param editorEnvironment   whether the run is an editor test run
     * @return a {@link ClusterElementContext} bound to that job principal
     */
    ClusterElementContext createClusterElementContext(
        String componentName, int componentVersion, String clusterElementName, @Nullable Long jobPrincipalId,
        @Nullable ComponentConnection componentConnection, @Nullable PlatformType type, boolean editorEnvironment);

    ClusterElementContext createClusterElementContext(
        String componentName, int componentVersion, String clusterElementName,
        @Nullable ComponentConnection componentConnection, boolean editorEnvironment,
        ClusterElementResolverFunction clusterElementResolverFunction);

    /**
     * Creates a {@link ClusterElementContext} that, when {@code agentActionContext} is non-null, returns that agent
     * {@link ActionContext} from {@link ClusterElementContext#toActionContext} instead of spawning a fresh one. This
     * lets AI agent tools (which reach their {@link ActionContext} via {@code toActionContext}) run against the live
     * agent execution so {@code suspend()}/{@code resume()} reach the agent. When {@code agentActionContext} is
     * {@code null} this behaves exactly like the overload without it.
     */
    ClusterElementContext createClusterElementContext(
        String componentName, int componentVersion, String clusterElementName,
        @Nullable ComponentConnection componentConnection, boolean editorEnvironment,
        @Nullable ActionContext agentActionContext);

    TriggerContext createTriggerContext(
        String componentName, int componentVersion, String triggerName, @Nullable Long jobPrincipalId,
        @Nullable String workflowUuid, @Nullable ComponentConnection componentConnection, @Nullable Long environmentId,
        @Nullable PlatformType type, boolean editorEnvironment);
}

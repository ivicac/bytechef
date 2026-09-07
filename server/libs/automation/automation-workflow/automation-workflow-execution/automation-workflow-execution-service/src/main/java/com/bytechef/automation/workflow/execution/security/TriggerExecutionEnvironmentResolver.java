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

package com.bytechef.automation.workflow.execution.security;

import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.security.ResourceEnvironmentResolver;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.execution.domain.TriggerExecution;
import com.bytechef.platform.workflow.execution.service.TriggerExecutionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reports the environment a trigger execution lives in: the environment of the project deployment its
 * {@link WorkflowExecutionId} names.
 *
 * <p>
 * A trigger execution genuinely carries an environment, so it opts in rather than keeping the environment-unaware
 * check. Without this, a {@code 'TriggerExecution'}-keyed gate would union every environment the caller can reach, and
 * a viewer in one environment would pass the by-id check on another's trigger execution — the same hole
 * {@code ProjectDeploymentEnvironmentResolver} closes for the deployment itself.
 *
 * @author Ivica Cardic
 */
@Component
public class TriggerExecutionEnvironmentResolver implements ResourceEnvironmentResolver {

    private final ProjectDeploymentService projectDeploymentService;
    private final TriggerExecutionService triggerExecutionService;

    @SuppressFBWarnings("EI")
    public TriggerExecutionEnvironmentResolver(
        ProjectDeploymentService projectDeploymentService, TriggerExecutionService triggerExecutionService) {

        this.projectDeploymentService = projectDeploymentService;
        this.triggerExecutionService = triggerExecutionService;
    }

    @Override
    public String resourceType() {
        return "TriggerExecution";
    }

    @Override
    public Optional<Environment> fetchEnvironment(Serializable id) {
        if (!(id instanceof Number number)) {
            return Optional.empty();
        }

        // The plural lookup, for the same reason TriggerExecutionOwnershipResolver uses it: the singular throws on an
        // absent id, and "gone" must answer empty here so the check falls back rather than failing.
        List<TriggerExecution> triggerExecutions = triggerExecutionService.getTriggerExecutions(
            List.of(number.longValue()));

        if (triggerExecutions.isEmpty()) {
            return Optional.empty();
        }

        TriggerExecution triggerExecution = triggerExecutions.getFirst();

        WorkflowExecutionId workflowExecutionId = triggerExecution.getWorkflowExecutionId();

        return projectDeploymentService.fetchProjectDeployment(workflowExecutionId.getJobPrincipalId())
            .map(ProjectDeployment::getEnvironment);
    }
}

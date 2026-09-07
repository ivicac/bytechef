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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.execution.domain.TriggerExecution;
import com.bytechef.platform.workflow.execution.service.TriggerExecutionService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Without this resolver a {@code 'TriggerExecution'}-keyed gate falls back to the environment-unaware check, which
 * unions every environment the caller can reach — a viewer in Development would pass the by-id check on a Production
 * trigger execution. The environment is the one of the deployment the trigger's {@code WorkflowExecutionId} names.
 *
 * @author Ivica Cardic
 */
class TriggerExecutionEnvironmentResolverTest {

    private static final long DEPLOYMENT_ID = 7L;
    private static final long TRIGGER_EXECUTION_ID = 3L;

    private final ProjectDeploymentService projectDeploymentService = mock(ProjectDeploymentService.class);
    private final TriggerExecutionService triggerExecutionService = mock(TriggerExecutionService.class);

    private final TriggerExecutionEnvironmentResolver resolver = new TriggerExecutionEnvironmentResolver(
        projectDeploymentService, triggerExecutionService);

    @Test
    void testResourceType() {
        assertThat(resolver.resourceType()).isEqualTo("TriggerExecution");
    }

    @Test
    void testResolvesTheEnvironmentOfTheDeploymentTheTriggerNames() {
        givenTriggerExecution();

        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setId(DEPLOYMENT_ID);
        projectDeployment.setEnvironment(Environment.PRODUCTION);

        when(projectDeploymentService.fetchProjectDeployment(DEPLOYMENT_ID)).thenReturn(
            Optional.of(projectDeployment));

        assertThat(resolver.fetchEnvironment(TRIGGER_EXECUTION_ID)).contains(Environment.PRODUCTION);
    }

    /**
     * Empty falls back to the environment-unaware check rather than denying, so an id of an unexpected shape or a row
     * that is gone must answer empty rather than throw.
     */
    @Test
    void testUnknownTriggerExecutionHasNoEnvironment() {
        when(triggerExecutionService.getTriggerExecutions(List.of(99L))).thenReturn(List.of());

        assertThat(resolver.fetchEnvironment(99L)).isEmpty();
    }

    @Test
    void testUnknownDeploymentHasNoEnvironment() {
        givenTriggerExecution();

        when(projectDeploymentService.fetchProjectDeployment(DEPLOYMENT_ID)).thenReturn(Optional.empty());

        assertThat(resolver.fetchEnvironment(TRIGGER_EXECUTION_ID)).isEmpty();
    }

    @Test
    void testNonNumericIdHasNoEnvironment() {
        assertThat(resolver.fetchEnvironment("not-a-number")).isEmpty();
    }

    private void givenTriggerExecution() {
        TriggerExecution triggerExecution = TriggerExecution.builder()
            .id(TRIGGER_EXECUTION_ID)
            .workflowExecutionId(
                WorkflowExecutionId.of(
                    PlatformType.AUTOMATION, DEPLOYMENT_ID, UUID.randomUUID()
                        .toString(),
                    "trigger_1"))
            .build();

        when(triggerExecutionService.getTriggerExecutions(List.of(TRIGGER_EXECUTION_ID))).thenReturn(
            List.of(triggerExecution));
    }
}

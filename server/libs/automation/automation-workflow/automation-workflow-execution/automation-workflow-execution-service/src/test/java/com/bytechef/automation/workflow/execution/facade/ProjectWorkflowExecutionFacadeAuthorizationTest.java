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

package com.bytechef.automation.workflow.execution.facade;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.automation.workflow.execution.security.TriggerExecutionOwnershipResolver;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the {@code @PreAuthorize} expressions that close automation workflow-execution IDOR (T24). Per-job reads resolve
 * the owning workspace via the {@code Job:ResourceRole} token (job &rarr; workflowId &rarr; project &rarr; workspace);
 * the workspace-scoped listing keys on the {@code workspaceId} argument.
 *
 * @author Ivica Cardic
 */
class ProjectWorkflowExecutionFacadeAuthorizationTest {

    @Test
    void testGetWorkflowExecutionRequiresJobViewer() {
        assertExpression("getWorkflowExecution", "hasPermission(#id, 'Job', 'EXECUTION_VIEW')");
    }

    @Test
    void testGetWorkflowExecutionTaskExecutionRequiresJobViewer() {
        assertExpression("getWorkflowExecutionTaskExecution", "hasPermission(#id, 'Job', 'EXECUTION_VIEW')");
    }

    /**
     * The listing keys on {@code workspaceId} <em>in the environment the request named</em>. It was
     * {@code hasPermission(#workspaceId, 'Workspace', 'EXECUTION_VIEW')} until ticket 732: no
     * {@code ResourceEnvironmentResolver} claims {@code 'Workspace'}, so that expression fell through to the
     * environment-unaware check and unioned every environment the caller could reach.
     * <p>
     * {@code hasWorkspaceScopeInEnvironmentId} rather than the {@code Environment}-taking sibling because
     * {@code environmentId} is a nullable {@code Long} here, and because neither this gate nor the method body
     * substitutes the principal's own environment -- both read the argument as sent, so both compute the same one. A
     * null still takes the union path on purpose: the request names no environment, so there is nothing to check, and
     * denying would 403 the ordinary unfiltered page. What a null caller sees is narrowed by the body instead, through
     * {@code EnvironmentScopeFilter}.
     */
    @Test
    void testGetWorkflowExecutionsRequiresWorkspaceViewerInTheNamedEnvironment() {
        assertExpression(
            "getWorkflowExecutions",
            "hasWorkspaceScopeInEnvironmentId(#workspaceId, 'EXECUTION_VIEW', #environmentId)");
    }

    /**
     * The trigger-row read keys on {@code triggerExecutionId}, not on a job id: the rows this endpoint serves are
     * precisely the trigger executions that never produced a job, so {@code 'Job'} could not resolve them. The
     * {@code 'TriggerExecution'} token resolves trigger execution &rarr; project deployment &rarr; project &rarr;
     * workspace instead.
     */
    @Test
    void testGetTriggerExecutionWorkflowExecutionRequiresExecutionView() {
        assertExpression(
            "getTriggerExecutionWorkflowExecution",
            "hasPermission(#triggerExecutionId, 'TriggerExecution', 'EXECUTION_VIEW')");
    }

    /**
     * {@code #triggerExecutionId} is resolved by name at runtime against the method's real parameter name. Renaming the
     * parameter would make the expression evaluate against null rather than fail loudly, so bind the two together here.
     * Requires {@code -parameters}, which the build sets; asserted rather than passed over vacuously.
     */
    @Test
    void testTriggerExecutionGateSpelArgumentNameMatchesTheMethodParameter() throws NoSuchMethodException {
        Method method = ProjectWorkflowExecutionFacadeImpl.class.getMethod(
            "getTriggerExecutionWorkflowExecution", long.class);

        assertThat(method.getParameters()[0].isNamePresent())
            .as("compiled without -parameters, so SpEL argument names cannot be verified")
            .isTrue();

        assertThat(method.getParameters())
            .extracting(Parameter::getName)
            .contains("triggerExecutionId");
    }

    /**
     * The {@code 'TriggerExecution'} token is only a live gate while a resolver claims that exact resourceType: an
     * unclaimed type makes {@code hasResourceScope} return false for every caller, turning the read into a blanket 403
     * that a tenant admin (who short-circuits earlier) would never notice. Bind token and resolver together.
     */
    @Test
    void testTriggerExecutionTokenIsClaimedByAResolver() {
        assertThat(new TriggerExecutionOwnershipResolver(null, null, null).resourceType())
            .isEqualTo("TriggerExecution");
    }

    private static void assertExpression(String methodName, String expression) {
        Method match = null;

        for (Method candidate : ProjectWorkflowExecutionFacadeImpl.class.getDeclaredMethods()) {
            if (candidate.getName()
                .equals(methodName) && candidate.isAnnotationPresent(PreAuthorize.class)) {

                match = candidate;

                break;
            }
        }

        assertThat(match)
            .as("@PreAuthorize-annotated method %s", methodName)
            .isNotNull();
        assertThat(match.getAnnotation(PreAuthorize.class)
            .value()).isEqualTo(expression);
    }
}

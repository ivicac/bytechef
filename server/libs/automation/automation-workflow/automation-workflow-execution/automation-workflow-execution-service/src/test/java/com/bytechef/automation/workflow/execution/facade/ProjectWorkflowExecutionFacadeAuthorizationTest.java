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

import java.lang.reflect.Method;
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

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

package com.bytechef.platform.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the {@code @PreAuthorize} expressions that close workflow-node-parameter IDOR (T22). Six of the eight operations
 * key on their {@code workflowId} AND the caller-supplied {@code environmentId} via
 * {@code hasWorkflowScopeInEnvironment(#workflowId, ..., #environmentId)}, which substitutes a confined principal's own
 * environment and checks per-environment scope rather than unioning across every environment the caller happens to hold
 * a scope in; reads require {@code WORKFLOW_VIEW}, mutations {@code WORKFLOW_EDIT}. The remaining two --
 * {@code getClusterElementMissingRequiredProperties} and {@code getWorkflowNodeMissingRequiredProperties} -- take no
 * {@code environmentId} and stay on the environment-agnostic {@code hasPermission(#workflowId, 'Workflow', ...)}. These
 * facade methods are invoked only by the workflow-editor REST/GraphQL controllers (no worker/execution callers), so a
 * per-workflow gate is safe.
 *
 * @author Ivica Cardic
 */
class WorkflowNodeParameterFacadeAuthorizationTest {

    @Test
    void testDeleteClusterElementParameterRequiresEdit() {
        assertEnvironmentAwareExpression("deleteClusterElementParameter", "WORKFLOW_EDIT");
    }

    @Test
    void testDeleteWorkflowNodeParameterRequiresEdit() {
        assertEnvironmentAwareExpression("deleteWorkflowNodeParameter", "WORKFLOW_EDIT");
    }

    @Test
    void testGetClusterElementDisplayConditionsRequiresView() {
        assertEnvironmentAwareExpression("getClusterElementDisplayConditions", "WORKFLOW_VIEW");
    }

    @Test
    void testGetClusterElementMissingRequiredPropertiesRequiresView() {
        assertExpression("getClusterElementMissingRequiredProperties", "WORKFLOW_VIEW");
    }

    @Test
    void testGetWorkflowNodeDisplayConditionsRequiresView() {
        assertEnvironmentAwareExpression("getWorkflowNodeDisplayConditions", "WORKFLOW_VIEW");
    }

    @Test
    void testGetWorkflowNodeMissingRequiredPropertiesRequiresView() {
        assertExpression("getWorkflowNodeMissingRequiredProperties", "WORKFLOW_VIEW");
    }

    @Test
    void testUpdateClusterElementParameterRequiresEdit() {
        assertEnvironmentAwareExpression("updateClusterElementParameter", "WORKFLOW_EDIT");
    }

    @Test
    void testUpdateWorkflowNodeParameterRequiresEdit() {
        assertEnvironmentAwareExpression("updateWorkflowNodeParameter", "WORKFLOW_EDIT");
    }

    /**
     * The workflow-free evaluation has no workflowId to scope a permission to, so it carries isAuthenticated() instead.
     * Pinned because it sits among methods that all guard on a workflow: adding it originally displaced
     * getClusterElementDisplayConditions' own annotation, silently dropping that method's guard.
     */
    @Test
    void testGetDisplayConditionsRequiresAuthentication() {
        Method match = null;

        for (Method candidate : WorkflowNodeParameterFacadeImpl.class.getDeclaredMethods()) {
            if (candidate.getName()
                .equals("getDisplayConditions") && candidate.isAnnotationPresent(PreAuthorize.class)) {

                match = candidate;

                break;
            }
        }

        assertThat(match)
            .as("@PreAuthorize-annotated method getDisplayConditions")
            .isNotNull();
        assertThat(match.getAnnotation(PreAuthorize.class)
            .value()).isEqualTo("isAuthenticated()");
    }

    private static void assertExpression(String methodName, String scope) {
        Method match = findPreAuthorizeMethod(methodName);

        assertThat(match.getAnnotation(PreAuthorize.class)
            .value()).isEqualTo("hasPermission(#workflowId, 'Workflow', '" + scope + "')");
    }

    private static void assertEnvironmentAwareExpression(String methodName, String scope) {
        Method match = findPreAuthorizeMethod(methodName);

        assertThat(match.getAnnotation(PreAuthorize.class)
            .value()).isEqualTo("hasWorkflowScopeInEnvironment(#workflowId, '" + scope + "', #environmentId)");
    }

    private static Method findPreAuthorizeMethod(String methodName) {
        Method match = null;

        for (Method candidate : WorkflowNodeParameterFacadeImpl.class.getDeclaredMethods()) {
            if (candidate.getName()
                .equals(methodName) && candidate.isAnnotationPresent(PreAuthorize.class)) {

                match = candidate;

                break;
            }
        }

        assertThat(match)
            .as("@PreAuthorize-annotated method %s", methodName)
            .isNotNull();

        return match;
    }
}

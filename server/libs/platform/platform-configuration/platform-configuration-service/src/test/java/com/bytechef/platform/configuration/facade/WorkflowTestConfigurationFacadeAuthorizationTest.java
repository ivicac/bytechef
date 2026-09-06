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
 * Pins the workflow-test-configuration gates (T22, D2). Five of the six caller-supplied-{@code environmentId} methods
 * -- everything except {@code saveWorkflowTestConfiguration}, whose ordinal is carried in the request body rather than
 * a parameter -- resolve {@code PrincipalEnvironment.resolveEffectiveEnvironmentId(environmentId)} internally, so their
 * gate is the substituting {@code hasWorkflowScopeInEnvironment(#workflowId, 'SCOPE', #environmentId)}: gate and body
 * compute the same effective environment for a confined (api-key) principal, so they can never diverge. See
 * {@code WorkflowTestConfigurationFacadeEnvironmentTest} for the execution-side proof of that resolution, and
 * {@code WorkflowTestConfigurationFacadeDiscriminatingGateTest} for the substituting-expression discriminating pair.
 * {@code saveWorkflowTestConfiguration} stays on {@code hasPermission(#workflowTestConfiguration.workflowId,
 * 'Workflow', 'WORKFLOW_EDIT')} -- out of this task's scope; its own class-level comment records why.
 * {@code removeUnusedWorkflowTestConfigurationConnections} is an internal after-save event-listener cleanup (no user
 * controller caller) and stays ungated -- a negative assertion locks that in.
 *
 * @author Ivica Cardic
 */
class WorkflowTestConfigurationFacadeAuthorizationTest {

    @Test
    void testDeleteConnectionRequiresEdit() {
        assertExpression(
            "deleteWorkflowTestConfigurationConnection",
            "hasWorkflowScopeInEnvironment(#workflowId, 'WORKFLOW_EDIT', #environmentId)");
    }

    @Test
    void testFetchConfigurationRequiresView() {
        assertExpression(
            "fetchWorkflowTestConfiguration",
            "hasWorkflowScopeInEnvironment(#workflowId, 'WORKFLOW_VIEW', #environmentId)");
    }

    @Test
    void testGetConfigurationConnectionsRequiresView() {
        assertExpression(
            "getWorkflowTestConfigurationConnections",
            "hasWorkflowScopeInEnvironment(#workflowId, 'WORKFLOW_VIEW', #environmentId)");
    }

    @Test
    void testSaveConfigurationRequiresEdit() {
        assertExpression(
            "saveWorkflowTestConfiguration",
            "hasPermission(#workflowTestConfiguration.workflowId, 'Workflow', 'WORKFLOW_EDIT')");
    }

    @Test
    void testSaveClusterElementConnectionRequiresEdit() {
        assertExpression(
            "saveClusterElementTestConfigurationConnection",
            "hasWorkflowScopeInEnvironment(#workflowId, 'WORKFLOW_EDIT', #environmentId)");
    }

    @Test
    void testSaveConnectionRequiresEdit() {
        assertExpression(
            "saveWorkflowTestConfigurationConnection",
            "hasWorkflowScopeInEnvironment(#workflowId, 'WORKFLOW_EDIT', #environmentId)");
    }

    @Test
    void testSaveInputsRequiresEdit() {
        assertExpression(
            "saveWorkflowTestConfigurationInputs",
            "hasWorkflowScopeInEnvironment(#workflowId, 'WORKFLOW_EDIT', #environmentId)");
    }

    @Test
    void testRemoveUnusedConnectionsIsNotGated() {
        Method match = null;

        for (Method candidate : WorkflowTestConfigurationFacadeImpl.class.getDeclaredMethods()) {
            if (candidate.getName()
                .equals("removeUnusedWorkflowTestConfigurationConnections")) {

                match = candidate;

                break;
            }
        }

        assertThat(match)
            .as("removeUnusedWorkflowTestConfigurationConnections method")
            .isNotNull();
        assertThat(match.isAnnotationPresent(PreAuthorize.class))
            .as("internal after-save listener cleanup must NOT carry @PreAuthorize")
            .isFalse();
    }

    private static void assertExpression(String methodName, String expression) {
        Method match = null;

        for (Method candidate : WorkflowTestConfigurationFacadeImpl.class.getDeclaredMethods()) {
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

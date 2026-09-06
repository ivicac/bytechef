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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the five {@code @PreAuthorize} gates on {@link WorkflowNodeTestOutputFacadeImpl} (D2). All five are
 * {@code @WorkflowCacheEvict}, whose aspect reads the {@code @EnvironmentIdParam} argument at the call site, before the
 * method body runs -- see the class's own comment for why none of them may resolve
 * {@code PrincipalEnvironment.resolveEffectiveEnvironmentId(...)} internally, and why that is exactly why each gate
 * below is the NON-substituting {@code hasResourceScopeInEnvironmentId(...)} rather than
 * {@code hasWorkflowScopeInEnvironment(...)}: substituting would authorize a confined principal's own environment while
 * the aspect evicts whatever environmentId the caller actually passed.
 * <p>
 * The sixth public method, {@code saveWorkflowNodeTestOutput(WorkflowExecutionId, long, WebhookRequest)}, is the
 * runtime webhook-test callback (no user-facing controller caller, driven by a {@code WorkflowExecutionId} that already
 * carries the environment the job ran in) and carries no {@code @PreAuthorize} at all -- a negative assertion locks
 * that in.
 *
 * @author Ivica Cardic
 */
class WorkflowNodeTestOutputFacadeAuthorizationTest {

    private static final String EXPECTED_EXPRESSION =
        "hasResourceScopeInEnvironmentId(#workflowId, 'Workflow', 'WORKFLOW_EDIT', #environmentId)";

    @Test
    void testSaveClusterElementTestOutputOverloadsBothRequireEdit() {
        assertExpression("saveClusterElementTestOutput", EXPECTED_EXPRESSION);
    }

    @Test
    void testSaveWorkflowNodeSampleOutputRequiresEdit() {
        assertExpression("saveWorkflowNodeSampleOutput", EXPECTED_EXPRESSION);
    }

    @Test
    void testSaveWorkflowNodeTestOutputOverloadsBothRequireEdit() {
        List<Method> methods = new ArrayList<>();

        for (Method candidate : WorkflowNodeTestOutputFacadeImpl.class.getDeclaredMethods()) {
            if (candidate.getName()
                .equals("saveWorkflowNodeTestOutput") && candidate.isAnnotationPresent(PreAuthorize.class)) {

                methods.add(candidate);
            }
        }

        assertThat(methods)
            .as("@PreAuthorize-annotated saveWorkflowNodeTestOutput overloads")
            .hasSize(2);

        for (Method method : methods) {
            assertThat(method.getAnnotation(PreAuthorize.class)
                .value())
                    .as(
                        "@PreAuthorize value on saveWorkflowNodeTestOutput%s",
                        Arrays.toString(method.getParameterTypes()))
                    .isEqualTo(EXPECTED_EXPRESSION);
        }
    }

    @Test
    void testSaveWorkflowNodeTestOutputWebhookCallbackIsNotGated() {
        Method match = null;

        for (Method candidate : WorkflowNodeTestOutputFacadeImpl.class.getDeclaredMethods()) {
            if (candidate.getName()
                .equals("saveWorkflowNodeTestOutput") && !candidate.isAnnotationPresent(PreAuthorize.class)) {

                match = candidate;

                break;
            }
        }

        assertThat(match)
            .as("un-annotated saveWorkflowNodeTestOutput(WorkflowExecutionId, long, WebhookRequest) overload")
            .isNotNull();
    }

    /**
     * Asserts the expression on EVERY declared, {@code @PreAuthorize}-annotated method with this name, not just the
     * first one {@code getDeclaredMethods} happens to return -- {@code getDeclaredMethods} order is unspecified, and
     * {@code saveClusterElementTestOutput} is overloaded, so checking only one match would silently skip the other
     * overload's guard.
     */
    private static void assertExpression(String methodName, String expression) {
        List<Method> methods = new ArrayList<>();

        for (Method candidate : WorkflowNodeTestOutputFacadeImpl.class.getDeclaredMethods()) {
            if (candidate.getName()
                .equals(methodName) && candidate.isAnnotationPresent(PreAuthorize.class)) {

                methods.add(candidate);
            }
        }

        assertThat(methods)
            .as("@PreAuthorize-annotated method %s", methodName)
            .isNotEmpty();

        for (Method method : methods) {
            assertThat(method.getAnnotation(PreAuthorize.class)
                .value())
                    .as("@PreAuthorize value on %s%s", methodName, Arrays.toString(method.getParameterTypes()))
                    .isEqualTo(expression);
        }
    }
}

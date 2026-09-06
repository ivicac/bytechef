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

package com.bytechef.platform.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins the two {@code @PreAuthorize} gates on {@link WorkflowNodeTestOutputServiceImpl} (D2). Both are reached only
 * through {@code WorkflowNodeTestOutputApiController}, a different Spring bean injecting this service by its interface
 * -- a genuine cross-bean call that crosses the method-security proxy boundary, so {@code @PreAuthorize} here is live,
 * not a no-op.
 * <p>
 * The two sites differ in body shape and therefore in expression:
 * <ul>
 * <li>{@code checkWorkflowNodeTestOutputExists} resolves
 * {@code PrincipalEnvironment.resolveEffectiveEnvironmentId(environmentId)} itself (no {@code @Cacheable}/
 * {@code @WorkflowCacheEvict} on it, so resolving internally is safe), the identical resolution the substituting
 * {@code hasWorkflowScopeInEnvironment(...)} performs -- gate and body can never diverge. Formerly, per its own
 * comment, "an existence oracle across every environment": a confined principal could learn whether a workflow node had
 * been tested in an environment it could not itself read, because the old {@code hasPermission(#workflowId,
 * 'Workflow', ...)} gate never checked the ordinal and the repository queries did not even use it.</li>
 * <li>{@code deleteWorkflowNodeTestOutput} is {@code @WorkflowCacheEvict}, whose aspect reads the
 * {@code @EnvironmentIdParam} argument at the call site, before the body runs; its only caller,
 * {@code WorkflowNodeTestOutputApiController}, resolves the effective environment BEFORE calling in for exactly that
 * reason. So this method never resolves internally, and its gate is the NON-substituting
 * {@code hasResourceScopeInEnvironmentId(...)} -- the same reasoning as every {@code WorkflowNodeTestOutputFacadeImpl}
 * site.</li>
 * </ul>
 *
 * @author Ivica Cardic
 */
class WorkflowNodeTestOutputServiceAuthorizationTest {

    @Test
    void testCheckWorkflowNodeTestOutputExistsRequiresView() {
        assertExpression(
            "checkWorkflowNodeTestOutputExists",
            "hasWorkflowScopeInEnvironment(#workflowId, 'WORKFLOW_VIEW', #environmentId)");
    }

    @Test
    void testDeleteWorkflowNodeTestOutputRequiresEdit() {
        assertExpression(
            "deleteWorkflowNodeTestOutput",
            "hasResourceScopeInEnvironmentId(#workflowId, 'Workflow', 'WORKFLOW_EDIT', #environmentId)");
    }

    private static void assertExpression(String methodName, String expression) {
        Method match = null;

        for (Method candidate : WorkflowNodeTestOutputServiceImpl.class.getDeclaredMethods()) {
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

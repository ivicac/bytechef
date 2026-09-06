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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.bytechef.platform.configuration.cache.WorkflowCacheManager;
import com.bytechef.platform.configuration.repository.WorkflowNodeTestOutputRepository;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Discriminating pair for D2's NON-substituting expression, {@code hasResourceScopeInEnvironmentId(...)}, pinned on
 * {@link WorkflowNodeTestOutputServiceImpl#deleteWorkflowNodeTestOutput}. That method's {@code @WorkflowCacheEvict}
 * aspect reads the caller-supplied {@code environmentId} at the call site (before the method body runs), and its only
 * caller, {@code WorkflowNodeTestOutputApiController}, resolves the effective environment BEFORE calling in for that
 * reason -- so the method itself never substitutes, and the gate must check the caller-supplied ordinal exactly, not a
 * confined principal's own, or the two could diverge. See the method's own comment.
 * <p>
 * A single fixture holding {@code WORKFLOW_EDIT} in DEVELOPMENT only is permitted when the call names DEVELOPMENT and
 * denied when it names PRODUCTION, without resetting the fixture between the two calls -- proving the per-environment
 * branch is real, not merely reachable.
 * <p>
 * The stub root below stands in for {@code AutomationMethodSecurityExpressionRoot}, which lives in
 * {@code automation-configuration-service}; this is a platform module and must not depend on it -- the same reason
 * {@code WorkflowScopeGateTestSupport} (in the sibling {@code facade} test package) exists for
 * {@code hasWorkflowScopeInEnvironment(...)}. What is pinned here is the wiring: that the expression parses, resolves
 * to a method of this name and arity, and receives this caller's own {@code workflowId}, {@code 'Workflow'},
 * {@code 'WORKFLOW_EDIT'}, and its own {@code environmentId}.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = WorkflowNodeTestOutputServiceDiscriminatingGateTest.Config.class)
class WorkflowNodeTestOutputServiceDiscriminatingGateTest {

    private static final long DEVELOPMENT_ORDINAL = 0L;
    private static final long PRODUCTION_ORDINAL = 2L;
    private static final String WORKFLOW_ID = "workflow-1";

    @Autowired
    private ResourceScopeGateRecorder resourceScopeGateRecorder;

    @Autowired
    private WorkflowNodeTestOutputService workflowNodeTestOutputService;

    @BeforeEach
    void setUp() {
        resourceScopeGateRecorder.reset();

        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(
                "user@localhost.com", "n/a", List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testDeleteWorkflowNodeTestOutputPermitsDevelopmentAndDeniesProductionForSameMember() {
        resourceScopeGateRecorder.permitOnlyForEnvironment(DEVELOPMENT_ORDINAL);

        workflowNodeTestOutputService.deleteWorkflowNodeTestOutput(WORKFLOW_ID, "node-1", DEVELOPMENT_ORDINAL);

        assertThat(resourceScopeGateRecorder.getResourceId()).isEqualTo(WORKFLOW_ID);
        assertThat(resourceScopeGateRecorder.getResourceType()).isEqualTo("Workflow");
        assertThat(resourceScopeGateRecorder.getScope()).isEqualTo("WORKFLOW_EDIT");

        assertThatThrownBy(
            () -> workflowNodeTestOutputService.deleteWorkflowNodeTestOutput(
                WORKFLOW_ID, "node-1", PRODUCTION_ORDINAL))
                    .isInstanceOf(AccessDeniedException.class);
    }

    @SpringBootConfiguration
    @EnableMethodSecurity
    static class Config {

        @Bean
        CacheManager cacheManager() {
            return mock(CacheManager.class);
        }

        @Bean
        MethodSecurityExpressionHandler methodSecurityExpressionHandler(
            ResourceScopeGateRecorder resourceScopeGateRecorder) {

            return new ResourceScopeGateExpressionHandler(resourceScopeGateRecorder);
        }

        @Bean
        ResourceScopeGateRecorder resourceScopeGateRecorder() {
            return new ResourceScopeGateRecorder();
        }

        @Bean
        WorkflowCacheManager workflowCacheManager() {
            return mock(WorkflowCacheManager.class);
        }

        @Bean
        WorkflowNodeTestOutputRepository workflowNodeTestOutputRepository() {
            return mock(WorkflowNodeTestOutputRepository.class);
        }

        @Bean
        WorkflowNodeTestOutputService workflowNodeTestOutputService(
            CacheManager cacheManager, WorkflowCacheManager workflowCacheManager,
            WorkflowNodeTestOutputRepository workflowNodeTestOutputRepository) {

            return new WorkflowNodeTestOutputServiceImpl(
                cacheManager, workflowCacheManager, workflowNodeTestOutputRepository);
        }
    }

    /**
     * Records the arguments {@code hasResourceScopeInEnvironmentId(...)} actually received.
     * {@link #permitOnlyForEnvironment(Long)} answers based on the {@code environmentId} argument alone, so a single
     * fixture stands in for "a member holding the scope in one environment only" across two calls that name different
     * environments.
     */
    static final class ResourceScopeGateRecorder {

        private Long permittedEnvironmentId;
        private String resourceId;
        private String resourceType;
        private String scope;

        void reset() {
            permittedEnvironmentId = null;
            resourceId = null;
            resourceType = null;
            scope = null;
        }

        void permitOnlyForEnvironment(Long onlyEnvironmentId) {
            permittedEnvironmentId = onlyEnvironmentId;
        }

        String getResourceId() {
            return resourceId;
        }

        String getResourceType() {
            return resourceType;
        }

        String getScope() {
            return scope;
        }

        private boolean record(
            Serializable recordedResourceId, String recordedResourceType, String recordedScope,
            Long recordedEnvironmentId) {

            resourceId = String.valueOf(recordedResourceId);
            resourceType = recordedResourceType;
            scope = recordedScope;

            return Objects.equals(recordedEnvironmentId, permittedEnvironmentId);
        }
    }

    static final class ResourceScopeGateExpressionHandler extends DefaultMethodSecurityExpressionHandler {

        private final ResourceScopeGateRecorder resourceScopeGateRecorder;

        ResourceScopeGateExpressionHandler(ResourceScopeGateRecorder resourceScopeGateRecorder) {
            this.resourceScopeGateRecorder = resourceScopeGateRecorder;
        }

        @Override
        public EvaluationContext createEvaluationContext(
            Supplier<? extends Authentication> authentication, MethodInvocation methodInvocation) {

            StandardEvaluationContext context =
                (StandardEvaluationContext) super.createEvaluationContext(authentication, methodInvocation);

            ResourceScopeGateExpressionRoot root =
                new ResourceScopeGateExpressionRoot(authentication, methodInvocation, resourceScopeGateRecorder);

            root.setAuthorizationManagerFactory(getAuthorizationManagerFactory());
            root.setPermissionEvaluator(getPermissionEvaluator());
            root.setDefaultRolePrefix(getDefaultRolePrefix());

            context.setRootObject(root);

            return context;
        }
    }

    static final class ResourceScopeGateExpressionRoot extends SecurityExpressionRoot
        implements MethodSecurityExpressionOperations {

        private final MethodInvocation methodInvocation;
        private final ResourceScopeGateRecorder resourceScopeGateRecorder;

        private Object filterObject;
        private Object returnObject;

        ResourceScopeGateExpressionRoot(
            Supplier<? extends Authentication> authentication, MethodInvocation methodInvocation,
            ResourceScopeGateRecorder resourceScopeGateRecorder) {

            super(authentication::get);

            this.methodInvocation = methodInvocation;
            this.resourceScopeGateRecorder = resourceScopeGateRecorder;
        }

        public boolean hasResourceScopeInEnvironmentId(
            Serializable resourceId, String resourceType, String scope, Long environmentId) {

            return resourceScopeGateRecorder.record(resourceId, resourceType, scope, environmentId);
        }

        @Override
        public Object getFilterObject() {
            return filterObject;
        }

        @Override
        public void setFilterObject(Object filterObject) {
            this.filterObject = filterObject;
        }

        @Override
        public Object getReturnObject() {
            return returnObject;
        }

        @Override
        public void setReturnObject(Object returnObject) {
            this.returnObject = returnObject;
        }

        @Override
        public Object getThis() {
            return methodInvocation.getThis();
        }
    }
}

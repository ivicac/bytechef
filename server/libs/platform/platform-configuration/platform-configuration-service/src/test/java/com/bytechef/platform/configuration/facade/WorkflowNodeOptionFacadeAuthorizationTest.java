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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.domain.Workflow.Format;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.component.definition.ClusterElementDefinition.ClusterElementType;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.component.domain.Option;
import com.bytechef.platform.component.facade.ActionDefinitionFacade;
import com.bytechef.platform.component.facade.ClusterElementDefinitionFacade;
import com.bytechef.platform.component.facade.TriggerDefinitionFacade;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.configuration.facade.WorkflowScopeGateTestSupport.GateExpressionHandler;
import com.bytechef.platform.configuration.facade.WorkflowScopeGateTestSupport.GateRecorder;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.workflow.task.dispatcher.service.TaskDispatcherDefinitionService;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Behavioural and reflection coverage for the {@code @PreAuthorize} gates closing the missing-authorization hole on
 * {@link WorkflowNodeOptionFacadeImpl}. Both {@code getWorkflowNodeOptions} and {@code getClusterElementNodeOptions}
 * previously carried no authorization at all, so any authenticated principal in the tenant could read any workflow's
 * {@code vars} and test-configuration inputs, its test-configuration connection ids and its previous nodes' sample
 * outputs, and then have {@code executeOptions} make a live outbound call using the connection those lookups resolved.
 * The gate was later re-pointed from {@code hasPermission(#workflowId, 'Workflow', ...)} (which unions the caller's
 * scope across every environment) to {@code hasWorkflowScopeInEnvironment(#workflowId, ..., #environmentId)}, which
 * checks the caller-supplied environment specifically -- see the class carrying that expression for why.
 *
 * <p>
 * Both halves matter. The denial tests prove the gate is reached before any collaborator is touched — a gate that runs
 * after the leak has already happened is not a gate. The permit tests prove it is not a gate that denies everybody,
 * which would look secure and be a broken feature: with {@code WORKFLOW_VIEW} granted the method runs to completion and
 * returns its options. {@link #testGetWorkflowNodeOptionsPermitsDevelopmentAndDeniesProductionForSameMember()}
 * additionally proves the per-environment branch is real, not merely reachable: a single fixture that only holds the
 * scope in DEVELOPMENT is permitted when the caller names DEVELOPMENT and denied when it names PRODUCTION, without
 * resetting the fixture in between.
 *
 * <p>
 * The {@link GateRecorder}/{@link GateExpressionHandler} stub stands in for
 * {@code AutomationMethodSecurityExpressionRoot}, which lives in {@code automation-configuration-service}; this is a
 * platform module and must not depend on it. What is pinned here is therefore the wiring — that the expression parses,
 * resolves to a method of this name and arity, and hands it this caller's own {@code workflowId}, the
 * {@code 'WORKFLOW_VIEW'} scope, and its own {@code environmentId} — while the function's own semantics (ordinal
 * resolution, fail-closed on an unidentifiable environment, skip-mode ordering) are pinned by
 * {@code AutomationMethodSecurityExpressionRootTest} beside the production implementation. The connected-user leg,
 * where the real evaluator and the real {@code ConnectedUserResourceMembershipResolver} decide, is pinned by
 * {@code ConnectedUserResourceMembershipEnforcementIntTest}.
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = WorkflowNodeOptionFacadeAuthorizationTest.Config.class)
@ExtendWith(ObjectMapperSetupExtension.class)
class WorkflowNodeOptionFacadeAuthorizationTest {

    private static final long DEVELOPMENT_ENVIRONMENT_ID = 0L;
    private static final long PRODUCTION_ENVIRONMENT_ID = 2L;
    private static final long ENVIRONMENT_ID = DEVELOPMENT_ENVIRONMENT_ID;
    private static final String VIEW_EXPRESSION =
        "hasWorkflowScopeInEnvironment(#workflowId, 'WORKFLOW_VIEW', #environmentId)";
    private static final String WORKFLOW_ID = "workflow-1";

    private static final Workflow WORKFLOW_WITH_TRIGGER = new Workflow(
        WORKFLOW_ID,
        """
            {
                "triggers": [
                    {
                        "label": "Manual",
                        "name": "trigger-1",
                        "type": "manual/v1/manual"
                    }
                ]
            }
            """,
        Format.JSON);

    private static final Workflow WORKFLOW_WITH_CLUSTER_ELEMENT = new Workflow(
        WORKFLOW_ID,
        """
            {
                "tasks": [
                    {
                        "name": "node-1",
                        "type": "aiAgent/v1/chat",
                        "parameters": {},
                        "clusterElements": {
                            "model": {
                                "name": "openAi_1",
                                "type": "openAi/v1/model",
                                "parameters": {}
                            }
                        }
                    }
                ]
            }
            """,
        Format.JSON);

    @Autowired
    private ActionDefinitionFacade actionDefinitionFacade;

    @Autowired
    private ClusterElementDefinitionFacade clusterElementDefinitionFacade;

    @Autowired
    private ClusterElementDefinitionService clusterElementDefinitionService;

    @Autowired
    private Evaluator evaluator;

    @Autowired
    private GateRecorder gateRecorder;

    @Autowired
    private TriggerDefinitionFacade triggerDefinitionFacade;

    @Autowired
    private WorkflowEvaluationInputsFacade workflowEvaluationInputsFacade;

    @Autowired
    private WorkflowNodeOptionFacade workflowNodeOptionFacade;

    @Autowired
    private WorkflowNodeOutputFacade workflowNodeOutputFacade;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowTestConfigurationService workflowTestConfigurationService;

    @BeforeEach
    void setUp() {
        reset(
            actionDefinitionFacade, clusterElementDefinitionFacade, clusterElementDefinitionService, evaluator,
            triggerDefinitionFacade, workflowEvaluationInputsFacade, workflowNodeOutputFacade, workflowService,
            workflowTestConfigurationService);

        gateRecorder.reset();

        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(
                "user@localhost.com", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testGetWorkflowNodeOptionsDeniesCallerWithoutWorkflowViewScope() {
        gateRecorder.permit(false);

        assertThatThrownBy(
            () -> workflowNodeOptionFacade.getWorkflowNodeOptions(
                WORKFLOW_ID, "trigger-1", "property", List.of(), "search", ENVIRONMENT_ID))
                    .isInstanceOf(AccessDeniedException.class);

        assertThat(gateRecorder.getCallCount()).isEqualTo(1);
        assertThat(gateRecorder.getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(gateRecorder.getScope()).isEqualTo("WORKFLOW_VIEW");
        assertThat(gateRecorder.getEnvironmentId()).isEqualTo(ENVIRONMENT_ID);

        verifyNothingWasReadOrCalled();
    }

    /**
     * Proves the per-environment branch is real. A single fixture that only holds {@code WORKFLOW_VIEW} in DEVELOPMENT
     * is never reset between the two calls below, so the second call's denial cannot be attributed to a different
     * simulated caller -- only to the environment named in the request differing from the one the fixture grants.
     */
    @Test
    void testGetWorkflowNodeOptionsPermitsDevelopmentAndDeniesProductionForSameMember() {
        gateRecorder.permitOnlyForEnvironment(DEVELOPMENT_ENVIRONMENT_ID);

        stubTriggerBranch();

        List<Option> options = workflowNodeOptionFacade.getWorkflowNodeOptions(
            WORKFLOW_ID, "trigger-1", "property", List.of(), "search", DEVELOPMENT_ENVIRONMENT_ID);

        assertThat(options).isEmpty();

        assertThatThrownBy(
            () -> workflowNodeOptionFacade.getWorkflowNodeOptions(
                WORKFLOW_ID, "trigger-1", "property", List.of(), "search", PRODUCTION_ENVIRONMENT_ID))
                    .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void testGetWorkflowNodeOptionsPermitsCallerWithWorkflowViewScope() {
        gateRecorder.permit(true);

        stubTriggerBranch();

        List<Option> options = workflowNodeOptionFacade.getWorkflowNodeOptions(
            WORKFLOW_ID, "trigger-1", "property", List.of(), "search", ENVIRONMENT_ID);

        assertThat(options).isEmpty();
        assertThat(gateRecorder.getCallCount()).isEqualTo(1);
        assertThat(gateRecorder.getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(gateRecorder.getScope()).isEqualTo("WORKFLOW_VIEW");
    }

    @Test
    void testGetClusterElementNodeOptionsDeniesCallerWithoutWorkflowViewScope() {
        gateRecorder.permit(false);

        assertThatThrownBy(
            () -> workflowNodeOptionFacade.getClusterElementNodeOptions(
                WORKFLOW_ID, "node-1", "model", "openAi_1", "property", List.of(), "search", ENVIRONMENT_ID))
                    .isInstanceOf(AccessDeniedException.class);

        assertThat(gateRecorder.getCallCount()).isEqualTo(1);
        assertThat(gateRecorder.getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(gateRecorder.getScope()).isEqualTo("WORKFLOW_VIEW");
        assertThat(gateRecorder.getEnvironmentId()).isEqualTo(ENVIRONMENT_ID);

        verifyNothingWasReadOrCalled();
    }

    @Test
    void testGetClusterElementNodeOptionsPermitsCallerWithWorkflowViewScope() {
        gateRecorder.permit(true);

        stubClusterElementBranch();

        List<Option> options = workflowNodeOptionFacade.getClusterElementNodeOptions(
            WORKFLOW_ID, "node-1", "model", "openAi_1", "property", List.of(), "search", ENVIRONMENT_ID);

        assertThat(options).isEmpty();
        assertThat(gateRecorder.getCallCount()).isEqualTo(1);
        assertThat(gateRecorder.getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(gateRecorder.getScope()).isEqualTo("WORKFLOW_VIEW");
    }

    @Test
    void testGetWorkflowNodeOptionsRequiresWorkflowView() {
        assertExpression("getWorkflowNodeOptions");
    }

    @Test
    void testGetClusterElementNodeOptionsRequiresWorkflowView() {
        assertExpression("getClusterElementNodeOptions");
    }

    /**
     * Two obligations, not one. The read side ({@code workflowService}, the {@code vars} merge, the test-configuration
     * connection ids) is the payload of the leak this gate closes. The execute side
     * ({@code trigger}/{@code action}/{@code clusterElement} definition facades) is what turns a resolved
     * {@code connectionId} into a live outbound call, and is the thing the gate most needs to precede. Asserting both
     * survives a reordering of the method body, which asserting only the reads would not.
     */
    private void verifyNothingWasReadOrCalled() {
        verifyNoInteractions(
            workflowService, workflowEvaluationInputsFacade, workflowTestConfigurationService,
            workflowNodeOutputFacade, triggerDefinitionFacade, actionDefinitionFacade, clusterElementDefinitionFacade);
    }

    private void stubTriggerBranch() {
        when(workflowService.getWorkflow(WORKFLOW_ID)).thenReturn(WORKFLOW_WITH_TRIGGER);
        when(workflowEvaluationInputsFacade.getEvaluationInputs(anyString(), anyLong())).thenReturn(Map.of());
        when(workflowTestConfigurationService.fetchWorkflowTestConfigurationConnectionId(
            anyString(), anyString(), anyLong())).thenReturn(Optional.empty());
        when(evaluator.evaluate(anyMap(), anyMap(), anyBoolean()))
            .thenReturn(Map.of("name", "trigger-1", "type", "manual/v1/manual"));
        when(triggerDefinitionFacade.executeOptions(
            anyString(), anyInt(), anyString(), anyString(), anyMap(), anyList(), anyString(), any()))
                .thenReturn(List.of());
    }

    private void stubClusterElementBranch() {
        when(workflowService.getWorkflow(WORKFLOW_ID)).thenReturn(WORKFLOW_WITH_CLUSTER_ELEMENT);
        when(workflowEvaluationInputsFacade.getEvaluationInputs(anyString(), anyLong())).thenReturn(Map.of());
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeSampleOutputs(anyString(), anyString(), anyLong()))
            .thenReturn(Map.of());
        when(workflowTestConfigurationService.fetchWorkflowTestConfiguration(anyString(), anyLong()))
            .thenReturn(Optional.empty());
        when(clusterElementDefinitionService.getClusterElementType(anyString(), anyInt(), anyString()))
            .thenReturn(new ClusterElementType("model", "model", "Model"));
        when(evaluator.evaluate(anyMap(), anyMap())).thenReturn(Map.of());
        when(clusterElementDefinitionFacade.executeOptions(
            anyString(), anyInt(), anyString(), anyString(), anyMap(), anyMap(), anyList(), anyString(), any(),
            anyMap(), anyMap())).thenReturn(List.of());
    }

    private static void assertExpression(String methodName) {
        List<Method> methods = Arrays.stream(WorkflowNodeOptionFacadeImpl.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .filter(method -> method.getName()
                .equals(methodName))
            .toList();

        assertThat(methods)
            .as("Expected exactly one non-synthetic '%s' on WorkflowNodeOptionFacadeImpl", methodName)
            .hasSize(1);

        PreAuthorize preAuthorize = methods.get(0)
            .getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
            .as("@PreAuthorize on %s", methodName)
            .isNotNull();
        assertThat(preAuthorize.value()).isEqualTo(VIEW_EXPRESSION);
    }

    @SpringBootConfiguration
    @EnableMethodSecurity(proxyTargetClass = true)
    static class Config {

        @Bean
        ActionDefinitionFacade actionDefinitionFacade() {
            return mock(ActionDefinitionFacade.class);
        }

        @Bean
        ClusterElementDefinitionFacade clusterElementDefinitionFacade() {
            return mock(ClusterElementDefinitionFacade.class);
        }

        @Bean
        ClusterElementDefinitionService clusterElementDefinitionService() {
            return mock(ClusterElementDefinitionService.class);
        }

        @Bean
        Evaluator evaluator() {
            return mock(Evaluator.class);
        }

        @Bean
        GateRecorder gateRecorder() {
            return new GateRecorder();
        }

        @Bean
        MethodSecurityExpressionHandler methodSecurityExpressionHandler(GateRecorder gateRecorder) {
            return new GateExpressionHandler(gateRecorder);
        }

        @Bean
        TaskDispatcherDefinitionService taskDispatcherDefinitionService() {
            return mock(TaskDispatcherDefinitionService.class);
        }

        @Bean
        TriggerDefinitionFacade triggerDefinitionFacade() {
            return mock(TriggerDefinitionFacade.class);
        }

        @Bean
        WorkflowEvaluationInputsFacade workflowEvaluationInputsFacade() {
            return mock(WorkflowEvaluationInputsFacade.class);
        }

        @Bean
        WorkflowNodeOptionFacade workflowNodeOptionFacade(
            Evaluator evaluator, ActionDefinitionFacade actionDefinitionFacade,
            ClusterElementDefinitionFacade clusterElementDefinitionFacade,
            ClusterElementDefinitionService clusterElementDefinitionService,
            TaskDispatcherDefinitionService taskDispatcherDefinitionService,
            TriggerDefinitionFacade triggerDefinitionFacade, WorkflowService workflowService,
            WorkflowEvaluationInputsFacade workflowEvaluationInputsFacade,
            WorkflowNodeOutputFacade workflowNodeOutputFacade,
            WorkflowTestConfigurationService workflowTestConfigurationService) {

            return new WorkflowNodeOptionFacadeImpl(
                evaluator, actionDefinitionFacade, clusterElementDefinitionFacade, clusterElementDefinitionService,
                taskDispatcherDefinitionService, triggerDefinitionFacade, workflowService,
                workflowEvaluationInputsFacade, workflowNodeOutputFacade, workflowTestConfigurationService);
        }

        @Bean
        WorkflowNodeOutputFacade workflowNodeOutputFacade() {
            return mock(WorkflowNodeOutputFacade.class);
        }

        @Bean
        WorkflowService workflowService() {
            return mock(WorkflowService.class);
        }

        @Bean
        WorkflowTestConfigurationService workflowTestConfigurationService() {
            return mock(WorkflowTestConfigurationService.class);
        }
    }
}

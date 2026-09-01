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

package com.bytechef.platform.component.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ActionDefinition.PerformFunction;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.ComponentDefinitionRegistry;
import com.bytechef.platform.component.context.ContextFactory;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.definition.MultipleConnectionsPerformFunction;
import com.bytechef.platform.component.definition.MultipleConnectionsStreamPerformFunction;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer;
import com.bytechef.platform.constant.PlatformType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Pins the CE-side half of the component rule enforcement seam: {@link ActionDefinitionServiceImpl} calls
 * {@link ComponentRuleEnforcer#checkBeforePerform} before dispatching a perform and
 * {@link ComponentRuleEnforcer#recordAfterPerform} after it, on both the workflow ({@code executePerform}) and polyglot
 * ({@code executePerformForPolyglot}) paths, and does neither when there are no enforcers registered.
 *
 * @author Ivica Cardic
 */
class ActionDefinitionServiceImplRuleTest {

    /**
     * Records what it was asked about, and optionally refuses the call — enough to prove the chokepoint calls the SPI
     * with the right facts, without standing up the EE module.
     */
    private static final class RecordingComponentRuleEnforcer implements ComponentRuleEnforcer {

        private final List<ActionCall> beforeCalls = new ArrayList<>();
        private final List<ActionCall> afterCalls = new ArrayList<>();
        private final List<Object> afterOutputs = new ArrayList<>();
        private final boolean blocking;

        private RecordingComponentRuleEnforcer(boolean blocking) {
            this.blocking = blocking;
        }

        @Override
        public @Nullable String checkBeforePerform(ActionCall actionCall) {
            beforeCalls.add(actionCall);

            if (blocking) {
                return "Action '%s' of component '%s' was blocked by an administrator rule."
                    .formatted(actionCall.actionName(), actionCall.componentName());
            }

            return null;
        }

        @Override
        public void recordAfterPerform(ActionCall actionCall, @Nullable Object output) {
            afterCalls.add(actionCall);
            afterOutputs.add(output);
        }
    }

    @Test
    void testExecutePerformIsBlockedByAFiringRule() {
        RecordingComponentRuleEnforcer componentRuleEnforcer = new RecordingComponentRuleEnforcer(true);
        ComponentDefinitionRegistry componentDefinitionRegistry = mock(ComponentDefinitionRegistry.class);

        ActionDefinitionServiceImpl service = new ActionDefinitionServiceImpl(
            componentDefinitionRegistry, mock(ContextFactory.class), List.of(),
            List.of(componentRuleEnforcer));

        assertThatThrownBy(
            () -> service.executePerform(
                "slack", 1, "sendMessage", 1L, 1L, 1L, 1L, "workflow1", Map.of("channel", "C05"), Map.of(), Map.of(),
                1L, false, PlatformType.AUTOMATION, null, null, null))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("blocked by an administrator rule");

        assertThat(componentRuleEnforcer.beforeCalls).hasSize(1);

        ComponentRuleEnforcer.ActionCall actionCall = componentRuleEnforcer.beforeCalls.getFirst();

        assertThat(actionCall.componentName()).isEqualTo("slack");
        assertThat(actionCall.actionName()).isEqualTo("sendMessage");
        assertThat(actionCall.inputParameters()
            .get("channel")).isEqualTo("C05");
        assertThat(actionCall.jobId()).isEqualTo(1L);
        assertThat(actionCall.taskExecutionId()).isEqualTo(1L);
    }

    @Test
    void testExecutePerformCapturesTheWiredConnectionId() {
        RecordingComponentRuleEnforcer componentRuleEnforcer = new RecordingComponentRuleEnforcer(true);
        ComponentDefinitionRegistry componentDefinitionRegistry = mock(ComponentDefinitionRegistry.class);
        ComponentConnection componentConnection = new ComponentConnection("slack", 1, 42L, Map.of(), null);

        ActionDefinitionServiceImpl service = new ActionDefinitionServiceImpl(
            componentDefinitionRegistry, mock(ContextFactory.class), List.of(),
            List.of(componentRuleEnforcer));

        assertThatThrownBy(
            () -> service.executePerform(
                "slack", 1, "sendMessage", 1L, 1L, 1L, 1L, "workflow1", Map.of(),
                Map.of("slack", componentConnection), Map.of(), 1L, false, PlatformType.AUTOMATION, null, null, null))
                    .isInstanceOf(ConfigurationException.class);

        assertThat(componentRuleEnforcer.beforeCalls).hasSize(1);

        ComponentRuleEnforcer.ActionCall actionCall = componentRuleEnforcer.beforeCalls.getFirst();

        assertThat(actionCall.connectionId()).isEqualTo(42L);
    }

    @Test
    void testBlockedActionNeverReachesTheRegistry() {
        RecordingComponentRuleEnforcer componentRuleEnforcer = new RecordingComponentRuleEnforcer(true);
        ComponentDefinitionRegistry componentDefinitionRegistry = mock(ComponentDefinitionRegistry.class);

        ActionDefinitionServiceImpl service = new ActionDefinitionServiceImpl(
            componentDefinitionRegistry, mock(ContextFactory.class), List.of(),
            List.of(componentRuleEnforcer));

        assertThatThrownBy(
            () -> service.executePerform(
                "slack", 1, "sendMessage", 1L, 1L, 1L, 1L, "workflow1", Map.of(), Map.of(), Map.of(), 1L, false,
                PlatformType.AUTOMATION, null, null, null))
                    .isInstanceOf(ConfigurationException.class);

        // checkComponentVisible/checkActionVisible and checkRulesBeforePerform both run in doExecutePerform, ahead of
        // doExecutePerformInternal (which is where the registry is first touched, to resolve the action definition).
        // A firing rule throws from checkRulesBeforePerform, so doExecutePerformInternal is never entered and the
        // registry stays untouched.
        verifyNoInteractions(componentDefinitionRegistry);

        assertThat(componentRuleEnforcer.afterCalls).isEmpty();
    }

    @Test
    void testPolyglotPerformIsBlockedByAFiringRule() {
        RecordingComponentRuleEnforcer componentRuleEnforcer = new RecordingComponentRuleEnforcer(true);

        ActionDefinitionServiceImpl service = new ActionDefinitionServiceImpl(
            mock(ComponentDefinitionRegistry.class), mock(ContextFactory.class), List.of(),
            List.of(componentRuleEnforcer));

        assertThatThrownBy(
            () -> service.executePerformForPolyglot(
                "slack", 1, "sendMessage", Map.of(), null, Map.of(), Map.of(), null, mock(ActionContext.class)))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("blocked by an administrator rule");
    }

    @Test
    void testExecutePerformForPolyglotFiresAfterHookForSingleConnectionPerform() {
        RecordingComponentRuleEnforcer componentRuleEnforcer = new RecordingComponentRuleEnforcer(false);
        ComponentDefinitionRegistry componentDefinitionRegistry = mock(ComponentDefinitionRegistry.class);
        com.bytechef.component.definition.ActionDefinition actionDefinition =
            mock(com.bytechef.component.definition.ActionDefinition.class);
        PerformFunction performFunction = (inputParameters, connectionParameters, context) -> "single-result";

        when(componentDefinitionRegistry.getActionDefinition("slack", 1, "sendMessage")).thenReturn(actionDefinition);
        doReturn(Optional.of(performFunction)).when(actionDefinition)
            .getPerform();

        ActionDefinitionServiceImpl service = new ActionDefinitionServiceImpl(
            componentDefinitionRegistry, mock(ContextFactory.class), List.of(),
            List.of(componentRuleEnforcer));

        Object result = service.executePerformForPolyglot(
            "slack", 1, "sendMessage", Map.of(), null, Map.of(), Map.of(), null, mock(ActionContext.class));

        assertThat(result).isEqualTo("single-result");
        assertThat(componentRuleEnforcer.afterCalls).hasSize(1);
        assertThat(componentRuleEnforcer.afterOutputs).containsExactly("single-result");
    }

    @Test
    void testExecutePerformForPolyglotFiresAfterHookForMultipleConnectionsPerform() {
        RecordingComponentRuleEnforcer componentRuleEnforcer = new RecordingComponentRuleEnforcer(false);
        ComponentDefinitionRegistry componentDefinitionRegistry = mock(ComponentDefinitionRegistry.class);
        com.bytechef.component.definition.ActionDefinition actionDefinition =
            mock(com.bytechef.component.definition.ActionDefinition.class);
        MultipleConnectionsPerformFunction performFunction =
            (inputParameters, componentConnections, extensions, context) -> "multi-result";

        when(componentDefinitionRegistry.getActionDefinition("slack", 1, "sendMessage")).thenReturn(actionDefinition);
        doReturn(Optional.of(performFunction)).when(actionDefinition)
            .getPerform();

        ActionDefinitionServiceImpl service = new ActionDefinitionServiceImpl(
            componentDefinitionRegistry, mock(ContextFactory.class), List.of(),
            List.of(componentRuleEnforcer));

        Object result = service.executePerformForPolyglot(
            "slack", 1, "sendMessage", Map.of(), null, Map.of(), Map.of(), null, mock(ActionContext.class));

        assertThat(result).isEqualTo("multi-result");
        assertThat(componentRuleEnforcer.afterCalls).hasSize(1);
        assertThat(componentRuleEnforcer.afterOutputs).containsExactly("multi-result");
    }

    @Test
    void testExecutePerformForPolyglotStreamingRejectionSkipsAfterHook() {
        RecordingComponentRuleEnforcer componentRuleEnforcer = new RecordingComponentRuleEnforcer(false);
        ComponentDefinitionRegistry componentDefinitionRegistry = mock(ComponentDefinitionRegistry.class);
        com.bytechef.component.definition.ActionDefinition actionDefinition =
            mock(com.bytechef.component.definition.ActionDefinition.class);
        MultipleConnectionsStreamPerformFunction performFunction =
            (inputParameters, componentConnections, extensions, context) -> null;

        when(componentDefinitionRegistry.getActionDefinition("slack", 1, "sendMessage")).thenReturn(actionDefinition);
        doReturn(Optional.of(performFunction)).when(actionDefinition)
            .getPerform();

        ActionDefinitionServiceImpl service = new ActionDefinitionServiceImpl(
            componentDefinitionRegistry, mock(ContextFactory.class), List.of(),
            List.of(componentRuleEnforcer));

        assertThatThrownBy(
            () -> service.executePerformForPolyglot(
                "slack", 1, "sendMessage", Map.of(), null, Map.of(), Map.of(), null, mock(ActionContext.class)))
                    .isInstanceOf(IllegalArgumentException.class);

        assertThat(componentRuleEnforcer.beforeCalls).hasSize(1);
        assertThat(componentRuleEnforcer.afterCalls).isEmpty();
    }

    @Test
    void testExecutePerformFiresAfterHookForSingleConnectionPerform() {
        // Pins the main workflow path: every OTHER positive afterCalls/afterOutputs assertion in this class is on
        // executePerformForPolyglot. Deleting the recordRulesAfterPerform call in doExecutePerform left every other
        // test in this class green, because none of them reached the main path with a real perform result — only this
        // one does.
        RecordingComponentRuleEnforcer componentRuleEnforcer = new RecordingComponentRuleEnforcer(false);
        ComponentDefinitionRegistry componentDefinitionRegistry = mock(ComponentDefinitionRegistry.class);
        ContextFactory contextFactory = mock(ContextFactory.class);
        com.bytechef.component.definition.ActionDefinition actionDefinition =
            mock(com.bytechef.component.definition.ActionDefinition.class);
        PerformFunction performFunction = (inputParameters, connectionParameters, context) -> "single-result";
        ActionContext actionContext = mock(ActionContext.class);

        when(componentDefinitionRegistry.getActionDefinition("slack", 1, "sendMessage")).thenReturn(actionDefinition);
        when(actionDefinition.getResumePerform()).thenReturn(Optional.empty());
        doReturn(Optional.of(performFunction)).when(actionDefinition)
            .getPerform();
        when(
            contextFactory.createActionContext(
                any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                    .thenReturn(actionContext);

        ActionDefinitionServiceImpl service = new ActionDefinitionServiceImpl(
            componentDefinitionRegistry, contextFactory, List.of(), List.of(componentRuleEnforcer));

        Object result = service.executePerform(
            "slack", 1, "sendMessage", 1L, 1L, 1L, 1L, "workflow1", Map.of(), Map.of(), Map.of(), 1L, false,
            PlatformType.AUTOMATION, null, null, null);

        assertThat(result).isEqualTo("single-result");
        assertThat(componentRuleEnforcer.afterCalls).hasSize(1);
        assertThat(componentRuleEnforcer.afterOutputs).containsExactly("single-result");
    }

    @Test
    void testExecutePerformSkipsAfterHookOnSuspend() {
        RecordingComponentRuleEnforcer componentRuleEnforcer = new RecordingComponentRuleEnforcer(false);
        ComponentDefinitionRegistry componentDefinitionRegistry = mock(ComponentDefinitionRegistry.class);
        ContextFactory contextFactory = mock(ContextFactory.class);
        com.bytechef.component.definition.ActionDefinition actionDefinition =
            mock(com.bytechef.component.definition.ActionDefinition.class);
        PerformFunction performFunction = (inputParameters, connectionParameters, context) -> "unused";
        ActionContextAware actionContextAware = mock(ActionContextAware.class);
        ActionContext.Suspend suspend = new ActionContext.Suspend(Map.of(), null);

        when(componentDefinitionRegistry.getActionDefinition("slack", 1, "sendMessage")).thenReturn(actionDefinition);
        when(actionDefinition.getResumePerform()).thenReturn(Optional.empty());
        doReturn(Optional.of(performFunction)).when(actionDefinition)
            .getPerform();
        when(
            contextFactory.createActionContext(
                any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                    .thenReturn(actionContextAware);
        when(actionContextAware.getSuspend()).thenReturn(suspend);

        ActionDefinitionServiceImpl service = new ActionDefinitionServiceImpl(
            componentDefinitionRegistry, contextFactory, List.of(), List.of(componentRuleEnforcer));

        Object result = service.executePerform(
            "slack", 1, "sendMessage", 1L, 1L, 1L, 1L, "workflow1", Map.of(), Map.of(), Map.of(), 1L, false,
            PlatformType.AUTOMATION, null, null, null);

        assertThat(result).isInstanceOf(ActionContext.Suspend.class);
        assertThat(componentRuleEnforcer.beforeCalls).hasSize(1);
        assertThat(componentRuleEnforcer.afterCalls).isEmpty();
    }

    @Test
    void testNoEnforcersMeansNoGuardAndNoBehaviourChange() {
        ComponentDefinitionRegistry componentDefinitionRegistry = mock(ComponentDefinitionRegistry.class);

        ActionDefinitionServiceImpl service = new ActionDefinitionServiceImpl(
            componentDefinitionRegistry, mock(ContextFactory.class), List.of(), List.of());

        // With no enforcers the call proceeds to the registry, which is a bare mock and so cannot supply a perform
        // function — the failure is the pre-existing one, not a rule rejection.
        assertThatThrownBy(
            () -> service.executePerform(
                "slack", 1, "sendMessage", 1L, 1L, 1L, 1L, "workflow1", Map.of(), Map.of(), Map.of(), 1L, false,
                PlatformType.AUTOMATION, null, null, null))
                    .isNotInstanceOf(ConfigurationException.class);
    }
}

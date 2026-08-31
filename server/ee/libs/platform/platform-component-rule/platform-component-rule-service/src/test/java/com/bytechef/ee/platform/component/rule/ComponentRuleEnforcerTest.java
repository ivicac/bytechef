/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.audit.ComponentRuleAuditEvent;
import com.bytechef.ee.platform.component.rule.audit.ComponentRuleAuditPublisher;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.evaluator.SpelEvaluator;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer.ActionCall;
import com.bytechef.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Ticker;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ComponentRuleEnforcerTest {

    private static final ActionCall SEND_MESSAGE_TO_C05 = new ActionCall(
        "slack", "sendMessage", Map.of("channel", "C05QG7RF30A", "text", "hello"), 3L, 42L, 7L);

    private final ComponentRuleService componentRuleService = mock(ComponentRuleService.class);
    private final ComponentRuleAuditPublisher componentRuleAuditPublisher = mock(ComponentRuleAuditPublisher.class);
    private final Evaluator evaluator = SpelEvaluator.builder()
        .build();
    private final ComponentRuleEnforcerImpl componentRuleEnforcer =
        new ComponentRuleEnforcerImpl(componentRuleService, evaluator, componentRuleAuditPublisher);

    @Test
    void testNoRulesIsANoOp() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(List.of());

        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNull();

        verify(componentRuleAuditPublisher, never()).publish(any(), any());
    }

    @Test
    void testMatchingBeforeBlockRuleReturnsARefusalReason() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    1L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK,
                    "inputParameters['channel'] == 'C05QG7RF30A'")));

        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05))
            .contains("blocked by an administrator rule");

        verify(componentRuleAuditPublisher).publish(eq(ComponentRuleAuditEvent.RULE_BLOCKED), any());
    }

    @Test
    void testNonMatchingBeforeBlockRuleAllowsTheCall() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    1L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK,
                    "inputParameters['channel'] == 'C99NOMATCH'")));

        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNull();

        verify(componentRuleAuditPublisher, never()).publish(any(), any());
    }

    @Test
    void testMatchingBeforeTagRuleAllowsTheCallAndAudits() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    2L, "sendMessage", RulePhase.BEFORE, RuleAction.TAG,
                    "contains(inputParameters['channel'], 'C05')")));

        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNull();

        verify(componentRuleAuditPublisher).publish(eq(ComponentRuleAuditEvent.RULE_TAGGED), any());
    }

    @Test
    void testBlockWinsOverTagOnTheSameCall() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(2L, "sendMessage", RulePhase.BEFORE, RuleAction.TAG, "true"),
                newComponentRule(1L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "true")));

        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNotNull();

        verify(componentRuleAuditPublisher).publish(eq(ComponentRuleAuditEvent.RULE_BLOCKED), any());
        verify(componentRuleAuditPublisher, never()).publish(eq(ComponentRuleAuditEvent.RULE_TAGGED), any());
    }

    @Test
    void testNullActionNameRuleAppliesToEveryAction() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(newComponentRule(3L, null, RulePhase.BEFORE, RuleAction.BLOCK, "true")));

        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNotNull();
    }

    @Test
    void testRuleScopedToAnotherActionDoesNotFire() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(newComponentRule(4L, "deleteMessage", RulePhase.BEFORE, RuleAction.BLOCK, "true")));

        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNull();
    }

    @Test
    void testAfterRuleSeesOutputInItsContext() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    5L, "sendMessage", RulePhase.AFTER, RuleAction.TAG, "output['ok'] == false")));

        componentRuleEnforcer.recordAfterPerform(SEND_MESSAGE_TO_C05, Map.of("ok", false));

        verify(componentRuleAuditPublisher).publish(eq(ComponentRuleAuditEvent.RULE_TAGGED), any());
    }

    @Test
    void testOutputIsNotAvailableInTheBeforePhase() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    11L, "sendMessage", RulePhase.BEFORE, RuleAction.TAG, "output['ok'] == false")));

        // `output` has no meaning before the action has run, so the reference is unresolvable, the evaluated result
        // is not Boolean.TRUE, and the rule does not fire — the negative half of testAfterRuleSeesOutputInItsContext.
        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNull();

        verify(componentRuleAuditPublisher, never()).publish(any(), any());
    }

    @Test
    void testBeforeRuleIsNotEvaluatedInTheAfterPhase() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(newComponentRule(6L, "sendMessage", RulePhase.BEFORE, RuleAction.TAG, "true")));

        componentRuleEnforcer.recordAfterPerform(SEND_MESSAGE_TO_C05, Map.of());

        verify(componentRuleAuditPublisher, never()).publish(any(), any());
    }

    @Test
    void testAfterPhaseNeverThrows() {
        when(componentRuleService.getEnabledComponentRules("slack"))
            .thenThrow(new IllegalStateException("database is down"));

        assertThatCode(() -> componentRuleEnforcer.recordAfterPerform(SEND_MESSAGE_TO_C05, Map.of()))
            .doesNotThrowAnyException();
    }

    @Test
    void testUnresolvableConditionDoesNotBlock() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    7L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "missingRoot['whatever'] == 1")));

        // SpelEvaluator returns the original string for an unresolved reference rather than null or false, so the
        // result is not Boolean.TRUE and the rule does not fire. A BLOCK rule that cannot be evaluated fails open.
        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNull();
    }

    @Test
    void testRulesAreFetchedOncePerComponentAcrossBothPhases() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(List.of());

        componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05);
        componentRuleEnforcer.recordAfterPerform(SEND_MESSAGE_TO_C05, Map.of());
        componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05);

        verify(componentRuleService, times(1)).getEnabledComponentRules("slack");
    }

    @Test
    void testAuditPayloadCarriesTheFiringRule() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(newComponentRule(9L, "sendMessage", RulePhase.BEFORE, RuleAction.TAG, "true")));

        componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05);

        ArgumentCaptor<ComponentRuleAuditPublisher.ComponentRuleAuditPayload> payloadCaptor =
            ArgumentCaptor.forClass(ComponentRuleAuditPublisher.ComponentRuleAuditPayload.class);

        verify(componentRuleAuditPublisher).publish(eq(ComponentRuleAuditEvent.RULE_TAGGED), payloadCaptor.capture());

        ComponentRuleAuditPublisher.ComponentRuleAuditPayload payload = payloadCaptor.getValue();

        assertThat(payload.ruleId()).isEqualTo(9L);
        assertThat(payload.componentName()).isEqualTo("slack");
        assertThat(payload.actionName()).isEqualTo("sendMessage");
        assertThat(payload.phase()).isEqualTo("BEFORE");
        assertThat(payload.jobId()).isEqualTo(42L);
        assertThat(payload.taskExecutionId()).isEqualTo(7L);
    }

    @Test
    void testTheCacheRefetchesAfterItsTtlExpires() {
        FakeTicker fakeTicker = new FakeTicker();
        ComponentRuleEnforcerImpl expiringComponentRuleEnforcer = new ComponentRuleEnforcerImpl(
            componentRuleService, evaluator, componentRuleAuditPublisher, fakeTicker);

        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(List.of());

        expiringComponentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05);

        fakeTicker.advanceSeconds(9);

        expiringComponentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05);

        verify(componentRuleService, times(1)).getEnabledComponentRules("slack");

        fakeTicker.advanceSeconds(2);

        expiringComponentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05);

        verify(componentRuleService, times(2)).getEnabledComponentRules("slack");
    }

    @Test
    void testCacheIsScopedByTenant() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(newComponentRule(12L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "true")));

        TenantContext.runWithTenantId(
            "tenant_a", () -> assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNotNull());

        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(List.of());

        // tenant_b must not be served tenant_a's cached (blocking) rule — a cache keyed on componentName alone would
        // let this call through unblocked... or worse, block tenant_b using a rule tenant_b never configured.
        TenantContext.runWithTenantId(
            "tenant_b", () -> assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNull());
    }

    /**
     * Caffeine reads elapsed time through a {@link Ticker}, so a fake one makes the 10-second TTL testable without a
     * sleep.
     */
    private static final class FakeTicker implements Ticker {

        private long nanos;

        @Override
        public long read() {
            return nanos;
        }

        private void advanceSeconds(long seconds) {
            nanos += TimeUnit.SECONDS.toNanos(seconds);
        }
    }

    private static ComponentRule newComponentRule(
        long id, String actionName, RulePhase rulePhase, RuleAction ruleAction, String condition) {

        ComponentRule componentRule = new ComponentRule();

        componentRule.setId(id);
        componentRule.setComponentName("slack");
        componentRule.setActionName(actionName);
        componentRule.setPhase(rulePhase);
        componentRule.setRuleAction(ruleAction);
        componentRule.setCondition(condition);
        componentRule.setEnabled(true);

        return componentRule;
    }
}

/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.spend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.automation.ai.gateway.service.WorkspaceAiGatewaySpendService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewaySpendSummary;
import com.bytechef.ee.platform.ai.llm.usage.AiLlmUsage;
import com.bytechef.ee.platform.ai.llm.usage.LlmUsageSource;
import com.bytechef.ee.platform.ai.llm.usage.service.AiLlmUsageService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiGatewaySpendRollupJobTest {

    private final Instant periodStart = Instant.parse("2026-07-20T10:00:00Z");
    private final Instant periodEnd = periodStart.plus(1, ChronoUnit.HOURS);

    @Mock
    private AiLlmUsageService aiLlmUsageService;

    @Mock
    private WorkspaceAiGatewaySpendService workspaceAiGatewaySpendService;

    private AiGatewaySpendRollupJob job;

    @BeforeEach
    void beforeEach() {
        job = new AiGatewaySpendRollupJob(aiLlmUsageService, workspaceAiGatewaySpendService);

        when(aiLlmUsageService.findDistinctWorkspaceIds()).thenReturn(List.of(1L));
        when(workspaceAiGatewaySpendService.getSpendSummariesByWorkspaceId(anyLong(), any(), any()))
            .thenReturn(List.of());
    }

    @Test
    void testAggregatesUsageRowsPerModelGroup() {
        AiLlmUsage firstUsage = usage("gpt-4o", "openai", new BigDecimal("0.01"), 100, 20);
        AiLlmUsage secondUsage = usage("gpt-4o", "openai", new BigDecimal("0.02"), 200, 40);
        AiLlmUsage otherModelUsage = usage("claude-sonnet-5", "anthropic", new BigDecimal("0.05"), 50, 10);

        when(aiLlmUsageService.getRequestLogsByWorkspace(1L, periodStart, periodEnd))
            .thenReturn(List.of(firstUsage, secondUsage, otherModelUsage));

        job.rollUp(periodStart, periodEnd);

        ArgumentCaptor<AiGatewaySpendSummary> summaryCaptor = ArgumentCaptor.forClass(AiGatewaySpendSummary.class);

        verify(workspaceAiGatewaySpendService, org.mockito.Mockito.times(2))
            .createInWorkspace(summaryCaptor.capture(), eq(1L));

        List<AiGatewaySpendSummary> summaries = summaryCaptor.getAllValues();

        AiGatewaySpendSummary gptSummary = summaries.stream()
            .filter(summary -> "gpt-4o".equals(summary.getModel()))
            .findFirst()
            .orElseThrow();

        assertThat(gptSummary.getRequestCount()).isEqualTo(2);
        assertThat(gptSummary.getTotalInputTokens()).isEqualTo(300);
        assertThat(gptSummary.getTotalOutputTokens()).isEqualTo(60);
        assertThat(gptSummary.getTotalCost()).isEqualByComparingTo(new BigDecimal("0.03"));
        assertThat(gptSummary.getPeriodStart()).isEqualTo(periodStart);
        assertThat(gptSummary.getPeriodEnd()).isEqualTo(periodEnd);
    }

    @Test
    void testAlreadyRolledUpWindowIsSkipped() {
        AiGatewaySpendSummary existingSummary = new AiGatewaySpendSummary(periodStart, periodEnd);

        when(workspaceAiGatewaySpendService.getSpendSummariesByWorkspaceId(1L, periodStart, periodEnd))
            .thenReturn(List.of(existingSummary));

        job.rollUp(periodStart, periodEnd);

        verify(workspaceAiGatewaySpendService, never()).createInWorkspace(any(), anyLong());
    }

    @Test
    void testEmptyWindowWritesNothing() {
        when(aiLlmUsageService.getRequestLogsByWorkspace(1L, periodStart, periodEnd)).thenReturn(List.of());

        job.rollUp(periodStart, periodEnd);

        verify(workspaceAiGatewaySpendService, never()).createInWorkspace(any(), anyLong());
    }

    // Spec §7 / ⚑3: the rollup attributes spend to the connected user resolved onto the usage row (AiLlmUsage#userId,
    // stamped by AiGatewayFacadeImpl from the already-resolved embedded identity), never derived from apiKeyId — the
    // vendor's own gateway key, which stays constant across every one of that vendor's customers.
    @Test
    void testRollupAttributesSpendToTheResolvedConnectedUser() {
        AiLlmUsage connectedUserUsage = usage("gpt-4o", "openai", new BigDecimal("0.01"), 100, 20);

        connectedUserUsage.setUserId(5L);

        when(aiLlmUsageService.getRequestLogsByWorkspace(1L, periodStart, periodEnd))
            .thenReturn(List.of(connectedUserUsage));

        job.rollUp(periodStart, periodEnd);

        ArgumentCaptor<AiGatewaySpendSummary> summaryCaptor = ArgumentCaptor.forClass(AiGatewaySpendSummary.class);

        verify(workspaceAiGatewaySpendService).createInWorkspace(summaryCaptor.capture(), eq(1L));

        assertThat(summaryCaptor.getValue()
            .getConnectedUserId()).isEqualTo(5L);
    }

    @Test
    void testRollupDoesNotDeriveTheConnectedUserFromTheApiKey() {
        AiLlmUsage usage = usage("gpt-4o", "openai", new BigDecimal("0.01"), 100, 20);

        usage.setApiKeyId(99L);
        usage.setUserId(null);

        when(aiLlmUsageService.getRequestLogsByWorkspace(1L, periodStart, periodEnd))
            .thenReturn(List.of(usage));

        job.rollUp(periodStart, periodEnd);

        ArgumentCaptor<AiGatewaySpendSummary> summaryCaptor = ArgumentCaptor.forClass(AiGatewaySpendSummary.class);

        verify(workspaceAiGatewaySpendService).createInWorkspace(summaryCaptor.capture(), eq(1L));

        assertThat(summaryCaptor.getValue()
            .getConnectedUserId()).isNull();
    }

    // The Critical this round exists to close: ai_llm_usage.userId is shared by every writer, not exclusive to the
    // gateway. AiLlmUsageServiceImpl#recordLlm (the entry point AI Hub writes through) stamps userId with a PLATFORM
    // user id and source=AI_HUB — not a connected user id. Without the source guard in connectedUserIdOf, this row
    // would roll up with connectedUserId=5, and a customer numbered 5 would be charged (and could be rejected by the
    // budget cap) for an employee's AI Hub usage. Gateway rows are the ones that leave source null (AiGatewayFacadeImpl
    // never calls setSource), which is the positive signal the guard checks for.
    @Test
    void testRollupDoesNotAttributeAiHubUsageToAConnectedUser() {
        AiLlmUsage aiHubUsage = usage("gpt-4o", "openai", new BigDecimal("0.01"), 100, 20);

        aiHubUsage.setSource(LlmUsageSource.AI_HUB);
        aiHubUsage.setUserId(5L);

        when(aiLlmUsageService.getRequestLogsByWorkspace(1L, periodStart, periodEnd))
            .thenReturn(List.of(aiHubUsage));

        job.rollUp(periodStart, periodEnd);

        ArgumentCaptor<AiGatewaySpendSummary> summaryCaptor = ArgumentCaptor.forClass(AiGatewaySpendSummary.class);

        verify(workspaceAiGatewaySpendService).createInWorkspace(summaryCaptor.capture(), eq(1L));

        assertThat(summaryCaptor.getValue()
            .getConnectedUserId()).isNull();
    }

    // Fix round 2: the guard must also admit source == AI_GATEWAY, not just source == null. AiLlmUsage's own class
    // javadoc and AiLlmUsageServiceImpl#recordLlm's javadoc both already describe the gateway as writing rows through
    // forSuccess/forError, and both factories stamp source = AI_GATEWAY explicitly — a source == null-only guard would
    // silently zero out attribution the moment AiGatewayFacadeImpl's five construction sites are migrated to match
    // that documented contract, with no error anywhere to catch it.
    @Test
    void testRollupAttributesSpendForAGatewaySourcedUsageRow() {
        AiLlmUsage gatewaySourcedUsage = AiLlmUsage.forSuccess(
            "req-gateway-sourced", "gpt-4o", "gpt-4o", "openai", 200, 100, 20, 500, new BigDecimal("0.01"));

        gatewaySourcedUsage.setUserId(5L);

        when(aiLlmUsageService.getRequestLogsByWorkspace(1L, periodStart, periodEnd))
            .thenReturn(List.of(gatewaySourcedUsage));

        job.rollUp(periodStart, periodEnd);

        ArgumentCaptor<AiGatewaySpendSummary> summaryCaptor = ArgumentCaptor.forClass(AiGatewaySpendSummary.class);

        verify(workspaceAiGatewaySpendService).createInWorkspace(summaryCaptor.capture(), eq(1L));

        assertThat(summaryCaptor.getValue()
            .getConnectedUserId()).isEqualTo(5L);
    }

    @Test
    void testRollupSplitsSpendByConnectedUserWithinTheSameApiKey() {
        AiLlmUsage firstCustomerUsage = usage("gpt-4o", "openai", new BigDecimal("0.01"), 100, 20);
        AiLlmUsage secondCustomerUsage = usage("gpt-4o", "openai", new BigDecimal("0.02"), 200, 40);

        firstCustomerUsage.setApiKeyId(1L);
        firstCustomerUsage.setUserId(5L);
        secondCustomerUsage.setApiKeyId(1L);
        secondCustomerUsage.setUserId(6L);

        when(aiLlmUsageService.getRequestLogsByWorkspace(1L, periodStart, periodEnd))
            .thenReturn(List.of(firstCustomerUsage, secondCustomerUsage));

        job.rollUp(periodStart, periodEnd);

        ArgumentCaptor<AiGatewaySpendSummary> summaryCaptor = ArgumentCaptor.forClass(AiGatewaySpendSummary.class);

        verify(workspaceAiGatewaySpendService, org.mockito.Mockito.times(2))
            .createInWorkspace(summaryCaptor.capture(), eq(1L));

        List<Long> connectedUserIds = summaryCaptor.getAllValues()
            .stream()
            .map(AiGatewaySpendSummary::getConnectedUserId)
            .toList();

        assertThat(connectedUserIds).containsExactlyInAnyOrder(5L, 6L);
    }

    private static AiLlmUsage usage(
        String model, String provider, BigDecimal cost, int inputTokens, int outputTokens) {

        AiLlmUsage aiLlmUsage = new AiLlmUsage("req-" + model + "-" + cost, model);

        aiLlmUsage.setRoutedModel(model);
        aiLlmUsage.setRoutedProvider(provider);
        aiLlmUsage.setCost(cost);
        aiLlmUsage.setInputTokens(inputTokens);
        aiLlmUsage.setOutputTokens(outputTokens);

        return aiLlmUsage;
    }
}

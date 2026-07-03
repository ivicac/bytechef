/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.ai.hub.approval.repository.AiHubToolApprovalRepository;
import com.bytechef.ee.ai.hub.audit.AiHubAuditEvent;
import com.bytechef.ee.ai.hub.audit.AiHubAuditPublisher;
import com.bytechef.ee.ai.hub.metric.AiHubToolApprovalMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubToolApprovalServiceTest {

    private static final long CHAT_ID = 10L;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);

    private AiHubToolApprovalRepository repository;
    private AiHubToolApprovalMetrics metrics;
    private AiHubAuditPublisher auditPublisher;
    private AiHubToolApprovalServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(AiHubToolApprovalRepository.class);
        metrics = mock(AiHubToolApprovalMetrics.class);
        auditPublisher = mock(AiHubAuditPublisher.class);

        when(repository.save(any(AiHubToolApproval.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service = new AiHubToolApprovalServiceImpl(repository, metrics, auditPublisher, clock);
    }

    private static AiHubToolApproval pendingApproval() {
        AiHubToolApproval approval = new AiHubToolApproval();

        approval.setId(42L);
        approval.setChatId(CHAT_ID);
        approval.setThreadId("thread-1");
        approval.setRequestedByUserId(3L);
        approval.setToolKind(AiHubToolApproval.ToolKind.COMPONENT);
        approval.setToolName("sendEmail");
        approval.setComponentName("gmail");
        approval.setArguments("{}");
        approval.setMode("BUILD");
        approval.setEnvironment(0);

        return approval;
    }

    @Test
    void testCreatePendingStampsPendingStatusAndDefaultExpiry() {
        AiHubToolApproval approval = pendingApproval();

        AiHubToolApproval saved = service.createPending(approval);

        assertThat(saved.getStatus()).isEqualTo(AiHubToolApproval.Status.PENDING);
        assertThat(saved.getExpiresAt()).isEqualTo(Instant.now(clock)
            .plus(Duration.ofHours(24)));
    }

    @Test
    void testCreatePendingPublishesRequestedAuditEventWithRequiredPayloadKeys() {
        AiHubToolApproval approval = pendingApproval();

        service.createPending(approval);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);

        verify(auditPublisher).publish(eq(AiHubAuditEvent.AI_HUB_TOOL_APPROVAL_REQUESTED), dataCaptor.capture());

        Map<String, Object> data = dataCaptor.getValue();

        assertThat(data).containsEntry("approvalId", 42L)
            .containsEntry("chatId", CHAT_ID)
            .containsEntry("toolName", "sendEmail")
            .containsEntry("componentName", "gmail")
            .containsEntry("requestedByUserId", 3L);
    }

    @Test
    void testCreatePendingWithNullPublisherDoesNotThrow() {
        AiHubToolApprovalServiceImpl serviceWithoutPublisher =
            new AiHubToolApprovalServiceImpl(repository, metrics, null, clock);

        assertThatCode(() -> serviceWithoutPublisher.createPending(pendingApproval())).doesNotThrowAnyException();
    }

    @Test
    void testSupersedePendingFlipsEveryPendingRowAndRecordsMetric() {
        AiHubToolApproval first = pendingApproval();
        AiHubToolApproval second = pendingApproval();

        second.setId(43L);

        when(repository.findAllByChatIdAndStatus(CHAT_ID, AiHubToolApproval.Status.PENDING.ordinal()))
            .thenReturn(List.of(first, second));

        int superseded = service.supersedePending(CHAT_ID);

        assertThat(superseded).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(AiHubToolApproval.Status.SUPERSEDED);
        assertThat(second.getStatus()).isEqualTo(AiHubToolApproval.Status.SUPERSEDED);
        assertThat(first.getDecidedAt()).isEqualTo(Instant.now(clock));

        verify(repository, times(2)).save(any(AiHubToolApproval.class));
        verify(metrics, times(2)).record("superseded");
    }

    @Test
    void testSupersedePendingWithNoPendingRowsRecordsNoMetric() {
        when(repository.findAllByChatIdAndStatus(CHAT_ID, AiHubToolApproval.Status.PENDING.ordinal()))
            .thenReturn(List.of());

        int superseded = service.supersedePending(CHAT_ID);

        assertThat(superseded).isZero();
        verify(metrics, never()).record(any());
    }
}

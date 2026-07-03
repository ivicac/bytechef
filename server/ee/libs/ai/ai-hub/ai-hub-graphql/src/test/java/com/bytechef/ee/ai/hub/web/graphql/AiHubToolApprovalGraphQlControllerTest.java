/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.ai.hub.approval.AiHubToolApproval;
import com.bytechef.ee.ai.hub.approval.AiHubToolApprovalFacade;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubToolApprovalGraphQlControllerTest {

    @Test
    void testAiHubToolApprovalsDelegatesToFacade() {
        AiHubToolApprovalFacade toolApprovalFacade = mock(AiHubToolApprovalFacade.class);

        AiHubToolApproval approval = mock(AiHubToolApproval.class);

        when(toolApprovalFacade.list(7L, 10L)).thenReturn(List.of(approval));

        AiHubToolApprovalGraphQlController controller = new AiHubToolApprovalGraphQlController(toolApprovalFacade);

        List<AiHubToolApproval> result = controller.aiHubToolApprovals(7L, 10L);

        assertThat(result).containsExactly(approval);
        verify(toolApprovalFacade).list(7L, 10L);
    }

    @Test
    void testResolveAiHubToolApprovalDelegatesWithSameArguments() {
        AiHubToolApprovalFacade toolApprovalFacade = mock(AiHubToolApprovalFacade.class);

        AiHubToolApproval approval = mock(AiHubToolApproval.class);
        AiHubToolApprovalFacade.Resolution resolution = new AiHubToolApprovalFacade.Resolution(approval, true, "run-1");

        when(toolApprovalFacade.resolve(7L, 99L, true, "looks fine")).thenReturn(resolution);

        AiHubToolApprovalGraphQlController controller = new AiHubToolApprovalGraphQlController(toolApprovalFacade);

        AiHubToolApprovalFacade.Resolution result =
            controller.resolveAiHubToolApproval(7L, 99L, true, "looks fine");

        assertThat(result).isSameAs(resolution);
        verify(toolApprovalFacade).resolve(7L, 99L, true, "looks fine");
    }

    @Test
    void testToolApprovalToolKindResolverReturnsEnumName() {
        AiHubToolApprovalFacade toolApprovalFacade = mock(AiHubToolApprovalFacade.class);

        AiHubToolApproval approval = new AiHubToolApproval();

        approval.setToolKind(AiHubToolApproval.ToolKind.COMPONENT);

        AiHubToolApprovalGraphQlController controller = new AiHubToolApprovalGraphQlController(toolApprovalFacade);

        assertThat(controller.toolApprovalToolKind(approval)).isEqualTo("COMPONENT");
    }

    @Test
    void testToolApprovalStatusResolverReturnsEnumName() {
        AiHubToolApprovalFacade toolApprovalFacade = mock(AiHubToolApprovalFacade.class);

        AiHubToolApproval approval = new AiHubToolApproval();

        approval.setStatus(AiHubToolApproval.Status.APPROVED);

        AiHubToolApprovalGraphQlController controller = new AiHubToolApprovalGraphQlController(toolApprovalFacade);

        assertThat(controller.toolApprovalStatus(approval)).isEqualTo("APPROVED");
    }

    @Test
    void testToolApprovalDecidedAtResolverReturnsNullWhenUndecided() {
        AiHubToolApprovalFacade toolApprovalFacade = mock(AiHubToolApprovalFacade.class);

        AiHubToolApproval approval = new AiHubToolApproval();

        AiHubToolApprovalGraphQlController controller = new AiHubToolApprovalGraphQlController(toolApprovalFacade);

        assertThat(controller.toolApprovalDecidedAt(approval)).isNull();
    }

    @Test
    void testToolApprovalDecidedAtResolverReturnsEpochMilli() {
        AiHubToolApprovalFacade toolApprovalFacade = mock(AiHubToolApprovalFacade.class);

        Instant decidedAt = Instant.parse("2026-09-02T00:00:00Z");

        AiHubToolApproval approval = new AiHubToolApproval();

        approval.setDecidedAt(decidedAt);

        AiHubToolApprovalGraphQlController controller = new AiHubToolApprovalGraphQlController(toolApprovalFacade);

        assertThat(controller.toolApprovalDecidedAt(approval)).isEqualTo(decidedAt.toEpochMilli());
    }

    @Test
    void testToolApprovalExpiresAtResolverReturnsEpochMilli() {
        AiHubToolApprovalFacade toolApprovalFacade = mock(AiHubToolApprovalFacade.class);

        Instant expiresAt = Instant.parse("2026-09-03T00:00:00Z");

        AiHubToolApproval approval = new AiHubToolApproval();

        approval.setExpiresAt(expiresAt);

        AiHubToolApprovalGraphQlController controller = new AiHubToolApprovalGraphQlController(toolApprovalFacade);

        assertThat(controller.toolApprovalExpiresAt(approval)).isEqualTo(expiresAt.toEpochMilli());
    }

    /**
     * {@code createdDate} is Spring-Data-auditing-managed with no public setter, so a mock stands in for a real
     * instance here.
     */
    @Test
    void testToolApprovalCreatedDateResolverReturnsEpochMilli() {
        AiHubToolApprovalFacade toolApprovalFacade = mock(AiHubToolApprovalFacade.class);

        Instant createdDate = Instant.parse("2026-09-01T00:00:00Z");

        AiHubToolApproval approval = mock(AiHubToolApproval.class);

        when(approval.getCreatedDate()).thenReturn(createdDate);

        AiHubToolApprovalGraphQlController controller = new AiHubToolApprovalGraphQlController(toolApprovalFacade);

        assertThat(controller.toolApprovalCreatedDate(approval)).isEqualTo(createdDate.toEpochMilli());
    }
}

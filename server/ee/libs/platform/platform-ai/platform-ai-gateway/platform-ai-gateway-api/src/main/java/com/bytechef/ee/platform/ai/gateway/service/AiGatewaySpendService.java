/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.service;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewaySpendSummary;
import com.bytechef.ee.platform.ai.llm.usage.Money;
import java.time.Instant;
import java.util.List;

/**
 * CRUD for {@link AiGatewaySpendSummary}. A summary carries its owning workspace in its nullable {@code workspace_id}
 * column; the workspace-facing policy layer is {@code WorkspaceAiGatewaySpendService} in automation.
 *
 * @version ee
 */
public interface AiGatewaySpendService {

    AiGatewaySpendSummary create(AiGatewaySpendSummary summary);

    List<AiGatewaySpendSummary> getSpendSummaries(Instant start, Instant end);

    List<AiGatewaySpendSummary> getSpendSummariesByWorkspaceId(long workspaceId, Instant start, Instant end);

    /**
     * Sums {@link AiGatewaySpendSummary#getTotalCostAsMoney()} across every summary row attributed to
     * {@code connectedUserId} whose period starts inside {@code [start, end)} — the query {@code AiGatewayFacadeImpl}'s
     * per-connected-user budget cap check (spec §7) reads. Returned as {@link Money}, not a raw {@code BigDecimal}, for
     * the same reason {@code AiGatewayBudgetChecker#sumSpend} reads this same table as {@code Money}: a summary row in
     * a currency other than the caller's own must fail the comparison at add-time rather than silently sum into an
     * arithmetic-but-wrong number. Rows with a null {@code totalCost} (see {@code AiGatewaySpendRollupJob}'s javadoc on
     * why a cancelled-stream row can have one) are excluded from the sum rather than treated as $0, matching the rollup
     * job's own convention. Returns {@code Money.usd(BigDecimal.ZERO)} when no matching rows exist, never {@code null}
     * — the caller compares this directly against a cap with no null check of its own.
     */
    Money getTotalCostByConnectedUserId(long connectedUserId, Instant start, Instant end);
}

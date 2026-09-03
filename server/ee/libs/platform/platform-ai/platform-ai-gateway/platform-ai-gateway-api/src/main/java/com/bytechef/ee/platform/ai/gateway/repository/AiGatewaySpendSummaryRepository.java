/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.repository;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewaySpendSummary;
import java.time.Instant;
import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

/**
 * @version ee
 */
public interface AiGatewaySpendSummaryRepository extends ListCrudRepository<AiGatewaySpendSummary, Long> {

    List<AiGatewaySpendSummary> findAllByPeriodStartBetween(Instant start, Instant end);

    /**
     * Returns the spend summaries owned by the given workspace whose period starts inside the range. A summary with a
     * null {@code workspace_id} belongs to no workspace and is therefore never returned here — SQL equality never
     * matches NULL.
     */
    List<AiGatewaySpendSummary> findAllByWorkspaceIdAndPeriodStartBetween(
        long workspaceId, Instant start, Instant end);

    /**
     * Returns the spend summaries attributed to the given connected user whose period starts inside the range. Backs
     * the per-connected-user budget cap check (spec §7) — exercises the {@code (connected_user_id, period_start)} index
     * added alongside the column. A summary with a null {@code connected_user_id} is never returned here, for the same
     * reason a null {@code workspace_id} is never returned by {@link #findAllByWorkspaceIdAndPeriodStartBetween}.
     */
    List<AiGatewaySpendSummary> findAllByConnectedUserIdAndPeriodStartBetween(
        long connectedUserId, Instant start, Instant end);
}

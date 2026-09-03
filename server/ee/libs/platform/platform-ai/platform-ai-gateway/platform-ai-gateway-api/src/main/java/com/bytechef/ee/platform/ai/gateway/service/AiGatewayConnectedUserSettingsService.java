/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.service;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayConnectedUserSettings;
import java.math.BigDecimal;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A connected user's assigned plan and spend cap. Unguarded, as every gateway service is: authorization lives on the
 * embedded tier's {@code ConnectedUserAiGatewayFacade}.
 *
 * @version ee
 */
public interface AiGatewayConnectedUserSettingsService {

    /**
     * Assigns {@code routingPolicyId} as the connected user's plan, creating the row when absent and replacing any
     * previous plan. Does not validate the policy; callers do.
     */
    void assignRoutingPolicy(long connectedUserId, long routingPolicyId);

    long countByRoutingPolicyId(long routingPolicyId);

    void deleteByConnectedUserId(long connectedUserId);

    Optional<AiGatewayConnectedUserSettings> fetchByConnectedUserId(long connectedUserId);

    /**
     * Clears the connected user's plan and keeps the row, so a cap set on it survives. A no-op when no row exists.
     */
    void unassignRoutingPolicy(long connectedUserId);

    /**
     * Sets the connected user's spend cap in USD, creating the row when absent; {@code null} clears it. A negative cap
     * is refused with {@code IllegalArgumentException("budgetCap must not be negative")}.
     */
    void updateBudgetCap(long connectedUserId, @Nullable BigDecimal budgetCap);
}

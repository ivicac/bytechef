/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.connected.user.gateway.facade;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Vendor-admin management of a connected user's AI Gateway settings: the plan (routing policy) it is assigned, its
 * spend cap, and its own provider credentials. Every method requires {@code ROLE_ADMIN}. A connected user id from
 * another tenant is indistinguishable from a missing one: both fail with
 * {@code IllegalArgumentException("Connected user not found: <id>")}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface ConnectedUserAiGatewayFacade {

    /**
     * Assigns a tenant-level routing policy as the connected user's plan, replacing any previous plan. Many connected
     * users may share one plan. A workspace-scoped policy is refused.
     */
    void assignRoutingPolicy(long connectedUserId, long routingPolicyId);

    /**
     * Creates a provider credential owned by the connected user for its whole life. A second provider of the same type
     * is refused until the first is deleted.
     */
    AiGatewayProvider createProvider(long connectedUserId, AiGatewayProvider provider);

    /**
     * Deletes one of the connected user's own providers. A provider that is not the connected user's fails exactly as a
     * missing one does.
     */
    void deleteProvider(long connectedUserId, long providerId);

    /**
     * Clears the connected user's plan. A no-op when none is assigned.
     */
    void unassignRoutingPolicy(long connectedUserId);

    /**
     * Sets the connected user's spend cap in USD; {@code null} clears it back to the embedded default cap. A negative
     * cap is refused.
     */
    void updateBudgetCap(long connectedUserId, @Nullable BigDecimal budgetCap);
}

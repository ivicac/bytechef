/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.service;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * CRUD for {@link AiGatewayRoutingPolicy}. A policy carries its owning workspace in its nullable {@code workspace_id}
 * column; the workspace-facing policy layer is {@code WorkspaceAiGatewayRoutingPolicyService} in automation.
 *
 * @version ee
 */
public interface AiGatewayRoutingPolicyService {

    AiGatewayRoutingPolicy create(AiGatewayRoutingPolicy policy);

    void delete(long id);

    /**
     * Returns the policy bound to the given connected user, if any. This is the highest-precedence level in the
     * gateway's routing resolution chain — a connected user's own policy outranks the model default and the embedded
     * default (see {@code AiGatewayFacadeImpl#applyRoutingPolicyPrecedence}).
     */
    Optional<AiGatewayRoutingPolicy> fetchRoutingPolicyByConnectedUserId(long connectedUserId);

    /**
     * Returns the default-tier policies — those bound to no workspace. Deliberately a separate method rather than a
     * nullable parameter on {@link #getRoutingPoliciesByWorkspaceId(long)}: overloading null onto a scope lookup would
     * make the automation path's primitive signature dishonest.
     */
    List<AiGatewayRoutingPolicy> getDefaultRoutingPolicies();

    AiGatewayRoutingPolicy getRoutingPolicy(long id);

    AiGatewayRoutingPolicy getRoutingPolicyByName(String name);

    List<AiGatewayRoutingPolicy> getRoutingPolicies();

    List<AiGatewayRoutingPolicy> getRoutingPolicies(Collection<Long> ids);

    List<AiGatewayRoutingPolicy> getRoutingPoliciesByWorkspaceId(long workspaceId);

    AiGatewayRoutingPolicy update(AiGatewayRoutingPolicy policy);

    /**
     * Sets the policy's bound connected user, or clears it when {@code connectedUserId} is null. Mirrors
     * {@link #updateWorkspaceId(long, Long)} for the connected-user scope; separate from
     * {@link #update(AiGatewayRoutingPolicy)} for the same reason that one is separate from
     * {@link #updateWorkspaceId(long, Long)} — a detached policy must not re-stamp its own binding.
     */
    void updateConnectedUserId(long id, @Nullable Long connectedUserId);

    /**
     * Sets the policy's owning workspace, or clears it when {@code workspaceId} is null. Separate from
     * {@link #update(AiGatewayRoutingPolicy)}, which deliberately copies only the caller-editable fields and must not
     * let a detached policy re-stamp its own ownership.
     */
    void updateWorkspaceId(long id, @Nullable Long workspaceId);
}

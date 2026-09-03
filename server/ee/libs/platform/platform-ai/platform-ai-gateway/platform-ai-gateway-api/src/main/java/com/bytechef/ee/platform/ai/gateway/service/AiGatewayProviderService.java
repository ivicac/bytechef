/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.service;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProviderType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * CRUD for {@link AiGatewayProvider}. A provider carries its owning workspace in its nullable {@code workspace_id}
 * column; the workspace-facing policy layer is {@code WorkspaceAiGatewayProviderService} in automation.
 *
 * @version ee
 */
public interface AiGatewayProviderService {

    AiGatewayProvider create(AiGatewayProvider provider);

    void delete(long id);

    /**
     * Returns the provider bound to the given connected user for the given provider type, if any — the BYOK
     * (bring-your-own-key) row that {@code AiGatewayProviderResolver} prefers over the tenant's own provider of the
     * same type. Mirrors {@link AiGatewayRoutingPolicyService#fetchRoutingPolicyByConnectedUserId(long)}.
     */
    Optional<AiGatewayProvider> fetchProviderByConnectedUserIdAndType(long connectedUserId, AiGatewayProviderType type);

    AiGatewayProvider getProvider(long id);

    List<AiGatewayProvider> getProviders(Collection<Long> ids);

    List<AiGatewayProvider> getProviders();

    List<AiGatewayProvider> getEnabledProviders();

    List<AiGatewayProvider> getProvidersByWorkspaceId(long workspaceId);

    AiGatewayProvider update(AiGatewayProvider provider);

    /**
     * Sets the provider's bound connected user, or clears it when {@code connectedUserId} is null. Mirrors
     * {@link #updateWorkspaceId(long, Long)} for the connected-user scope, and
     * {@link AiGatewayRoutingPolicyService#updateConnectedUserId(long, Long)} for the sibling entity; separate from
     * {@link #update(AiGatewayProvider)} for the same reason that one is separate from
     * {@link #updateWorkspaceId(long, Long)} — a detached provider must not re-stamp its own binding.
     */
    void updateConnectedUserId(long id, @Nullable Long connectedUserId);

    /**
     * Sets the provider's owning workspace, or clears it when {@code workspaceId} is null. Separate from
     * {@link #update(AiGatewayProvider)}, which deliberately copies only the caller-editable fields and must not let a
     * detached provider re-stamp its own ownership.
     */
    void updateWorkspaceId(long id, @Nullable Long workspaceId);
}

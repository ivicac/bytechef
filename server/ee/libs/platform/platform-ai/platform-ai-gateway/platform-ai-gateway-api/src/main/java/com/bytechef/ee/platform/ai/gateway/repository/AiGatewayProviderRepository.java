/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.repository;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayProvider;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

/**
 * @version ee
 */
public interface AiGatewayProviderRepository extends ListCrudRepository<AiGatewayProvider, Long> {

    List<AiGatewayProvider> findAllByEnabled(boolean enabled);

    /**
     * Returns the provider bound to the given connected user for the given provider type (stored ordinal), if any — the
     * BYOK lookup {@code AiGatewayProviderResolver} checks before falling back to the tenant's own provider of that
     * type. Backed by the partial unique index {@code uk_ai_gateway_provider_connected_user_id_type} on
     * {@code (connected_user_id, type) WHERE connected_user_id IS NOT NULL}, so at most one row can ever match — a
     * plain (non-{@code findFirst}) derived query, which Spring Data JDBC enforces by throwing
     * {@code IncorrectResultSizeDataAccessException} rather than silently picking one if that ever stops being true.
     */
    Optional<AiGatewayProvider> findByConnectedUserIdAndType(long connectedUserId, int type);

    /**
     * Returns the providers owned by the given workspace. A provider with a null {@code workspace_id} belongs to no
     * workspace and is therefore never returned here — SQL equality never matches NULL.
     */
    List<AiGatewayProvider> findAllByWorkspaceId(long workspaceId);
}

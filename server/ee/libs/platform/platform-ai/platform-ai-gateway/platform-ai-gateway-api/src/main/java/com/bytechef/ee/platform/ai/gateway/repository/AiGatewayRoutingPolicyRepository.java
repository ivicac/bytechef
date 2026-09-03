/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.repository;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

/**
 * @version ee
 */
public interface AiGatewayRoutingPolicyRepository extends ListCrudRepository<AiGatewayRoutingPolicy, Long> {

    Optional<AiGatewayRoutingPolicy> findByName(String name);

    /**
     * Returns the policy bound to the given connected user, if any. No environment parameter:
     * {@link AiGatewayRoutingPolicy} has no environment field and the gateway schema has no environment column —
     * environment arrives through the connected user id, which is already per-environment.
     */
    Optional<AiGatewayRoutingPolicy> findByConnectedUserId(long connectedUserId);

    List<AiGatewayRoutingPolicy> findAllByEnabled(boolean enabled);

    /**
     * Returns the routing policies owned by the given workspace. A policy with a null {@code workspace_id} belongs to
     * no workspace and is therefore never returned here — SQL equality never matches NULL.
     */
    List<AiGatewayRoutingPolicy> findAllByWorkspaceId(long workspaceId);

    List<AiGatewayRoutingPolicy> findAllByWorkspaceIdIsNull();
}

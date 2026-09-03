/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.connected.user.event;

import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayRoutingPolicyService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.relational.core.mapping.event.AbstractRelationalEventListener;
import org.springframework.data.relational.core.mapping.event.BeforeDeleteEvent;
import org.springframework.data.relational.core.mapping.event.Identifier;
import org.springframework.stereotype.Component;

/**
 * Unbinds -- never deletes -- the AI Gateway routing policy bound to a connected user before that connected user row is
 * removed (embedded phase 2 spec §5, ⚑7). The policy is a vendor-owned, admin-managed object that may be re-bound to a
 * different connected user later, so deleting a connected user must not cascade-delete it; only {@code
 * connected_user_id} is cleared.
 *
 * <p>
 * Follows {@code ProjectBeforeDeleteEventListener}'s shape: a Spring Data JDBC relational event hook rather than a
 * database foreign key, because there is no foreign key between {@code ai_gateway_routing_policy.connected_user_id} and
 * {@code connected_user.id} -- the platform tier's schema does not (and structurally should not) reference the embedded
 * tier's tables.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
public class ConnectedUserBeforeDeleteEventListener extends AbstractRelationalEventListener<ConnectedUser> {

    private final AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService;

    @SuppressFBWarnings("EI")
    public ConnectedUserBeforeDeleteEventListener(AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService) {
        this.aiGatewayRoutingPolicyService = aiGatewayRoutingPolicyService;
    }

    @Override
    protected void onBeforeDelete(BeforeDeleteEvent<ConnectedUser> event) {
        Identifier identifier = event.getId();

        long connectedUserId = (Long) identifier.getValue();

        Optional<AiGatewayRoutingPolicy> boundPolicy =
            aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(connectedUserId);

        if (boundPolicy.isPresent()) {
            aiGatewayRoutingPolicyService.updateConnectedUserId(boundPolicy.get()
                .getId(), null);
        }
    }
}

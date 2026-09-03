/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.connected.user.gateway.facade;

import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayRoutingPolicyService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.security.constant.AuthorityConstants;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link ConnectedUserAiGatewayRoutingPolicyFacade}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@Transactional
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
class ConnectedUserAiGatewayRoutingPolicyFacadeImpl implements ConnectedUserAiGatewayRoutingPolicyFacade {

    private final AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService;
    private final ConnectedUserService connectedUserService;

    @SuppressFBWarnings("EI")
    ConnectedUserAiGatewayRoutingPolicyFacadeImpl(
        AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService, ConnectedUserService connectedUserService) {

        this.aiGatewayRoutingPolicyService = aiGatewayRoutingPolicyService;
        this.connectedUserService = connectedUserService;
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public void bind(long routingPolicyId, long connectedUserId) {
        AiGatewayRoutingPolicy policy = aiGatewayRoutingPolicyService.getRoutingPolicy(routingPolicyId);

        // ck_ai_gateway_routing_policy_workspace_connected_user_not_both forbids both columns at once. A
        // workspace-scoped policy is not this facade's to touch -- WorkspaceAiGatewayRoutingPolicyServiceImpl owns
        // that binding -- so this is a domain error, caught here rather than surfacing as an unmapped
        // DataIntegrityViolationException out of updateConnectedUserId.
        if (policy.getWorkspaceId() != null) {
            throw new IllegalArgumentException(
                "Routing policy " + routingPolicyId + " is bound to a workspace and cannot be bound to a "
                    + "connected user");
        }

        requireConnectedUserExists(connectedUserId);

        Long currentPolicyOwner = policy.getConnectedUserId();

        // The policy is already bound to a DIFFERENT connected user. Unlike rebinding the same connected user to a
        // different policy (below), the party who would lose their routing here is a bystander the caller never
        // named -- silently moving the policy would leave them falling through to the embedded default with no
        // signal to anyone. Reject instead; the fix is for the caller to unbind that connected user explicitly.
        if (currentPolicyOwner != null && !currentPolicyOwner.equals(connectedUserId)) {
            throw new IllegalArgumentException(
                "Routing policy " + routingPolicyId + " is already bound to connected user " + currentPolicyOwner
                    + "; unbind it from that connected user first before binding it to connected user "
                    + connectedUserId);
        }

        Optional<AiGatewayRoutingPolicy> existingBoundPolicy =
            aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(connectedUserId);

        if (existingBoundPolicy.isPresent()) {
            AiGatewayRoutingPolicy previousPolicy = existingBoundPolicy.get();

            // A connected user may be bound to at most one policy (uk_ai_gateway_routing_policy_connected_user_id).
            // Rebinding a connected user to a different policy is a routine admin action -- e.g. moving a customer
            // to a different plan -- so this replaces the existing binding instead of failing on the constraint the
            // caller would otherwise have to retry around. Rebinding to the same policy is a harmless no-op below.
            // This is the mirror of the "already bound to someone else" guard above: here the party who loses their
            // binding IS the connected user the caller named, so replacing is the caller's own intent, not a
            // side effect on a bystander.
            if (!Objects.equals(previousPolicy.getId(), policy.getId())) {
                aiGatewayRoutingPolicyService.updateConnectedUserId(previousPolicy.getId(), null);
            }
        }

        aiGatewayRoutingPolicyService.updateConnectedUserId(policy.getId(), connectedUserId);
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public void unbind(long connectedUserId) {
        Optional<AiGatewayRoutingPolicy> boundPolicy =
            aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(connectedUserId);

        if (boundPolicy.isPresent()) {
            aiGatewayRoutingPolicyService.updateConnectedUserId(boundPolicy.get()
                .getId(), null);
        }
    }

    /**
     * Resolves the connected user within scope, never trusting the id alone -- mirrors {@code VariableServiceImpl}'s
     * by-id re-listing. This is a structural guarantee, not a defensive one: {@code BaseDataSource} switches the
     * connection's {@code search_path} to the caller's own tenant schema, so a foreign connected user id is not
     * rejected -- it is invisible. {@link ConnectedUserService#fetchConnectedUser(long)} therefore returns empty for a
     * foreign id exactly as it does for a genuinely nonexistent one; there is only one path, and it cannot diverge in
     * type or message between the two cases.
     */
    private void requireConnectedUserExists(long connectedUserId) {
        if (connectedUserService.fetchConnectedUser(connectedUserId)
            .isEmpty()) {

            throw new IllegalArgumentException("Connected user not found: " + connectedUserId);
        }
    }
}

/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import com.bytechef.ee.platform.ai.gateway.repository.AiGatewayRoutingPolicyRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link AiGatewayRoutingPolicyServiceImpl}. Covers the null-scope read path: a policy with no workspace
 * must be reachable through {@link AiGatewayRoutingPolicyService#getDefaultRoutingPolicies()} and must never leak into
 * {@link AiGatewayRoutingPolicyService#getRoutingPoliciesByWorkspaceId(long)}.
 *
 * @version ee
 */
@ExtendWith(MockitoExtension.class)
class AiGatewayRoutingPolicyServiceTest {

    @Mock
    private AiGatewayModelDeploymentService aiGatewayModelDeploymentService;

    @Mock
    private AiGatewayRoutingPolicyRepository aiGatewayRoutingPolicyRepository;

    private AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService;

    @BeforeEach
    void setUp() {
        aiGatewayRoutingPolicyService = new AiGatewayRoutingPolicyServiceImpl(
            aiGatewayModelDeploymentService, aiGatewayRoutingPolicyRepository);
    }

    @Test
    void testFetchRoutingPolicyByConnectedUserIdDelegatesToRepository() {
        AiGatewayRoutingPolicy connectedUserPolicy =
            new AiGatewayRoutingPolicy("connected-user-policy", AiGatewayRoutingStrategyType.SIMPLE);

        when(aiGatewayRoutingPolicyRepository.findByConnectedUserId(5L))
            .thenReturn(Optional.of(connectedUserPolicy));

        assertThat(aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(5L))
            .contains(connectedUserPolicy);
    }

    @Test
    void testFetchRoutingPolicyByConnectedUserIdReturnsEmptyWhenNoneBound() {
        when(aiGatewayRoutingPolicyRepository.findByConnectedUserId(5L)).thenReturn(Optional.empty());

        assertThat(aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(5L)).isEmpty();
    }

    @Test
    void testGetDefaultRoutingPoliciesReturnsNullScopedPolicies() {
        AiGatewayRoutingPolicy defaultPolicy =
            new AiGatewayRoutingPolicy("default", AiGatewayRoutingStrategyType.SIMPLE);

        when(aiGatewayRoutingPolicyRepository.findAllByWorkspaceIdIsNull()).thenReturn(List.of(defaultPolicy));

        List<AiGatewayRoutingPolicy> policies = aiGatewayRoutingPolicyService.getDefaultRoutingPolicies();

        assertThat(policies).containsExactly(defaultPolicy);
    }

    @Test
    void testGetRoutingPoliciesByWorkspaceIdDoesNotReturnNullScopedPolicies() {
        when(aiGatewayRoutingPolicyRepository.findAllByWorkspaceId(1L)).thenReturn(List.of());

        assertThat(aiGatewayRoutingPolicyService.getRoutingPoliciesByWorkspaceId(1L)).isEmpty();

        verify(aiGatewayRoutingPolicyRepository, never()).findAllByWorkspaceIdIsNull();
    }

    @Test
    void testUpdateConnectedUserIdSetsConnectedUserIdAndPersists() {
        AiGatewayRoutingPolicy policy = new AiGatewayRoutingPolicy("p", AiGatewayRoutingStrategyType.SIMPLE);

        when(aiGatewayRoutingPolicyRepository.findById(5L)).thenReturn(Optional.of(policy));
        when(aiGatewayRoutingPolicyRepository.save(policy)).thenReturn(policy);

        aiGatewayRoutingPolicyService.updateConnectedUserId(5L, 42L);

        assertThat(policy.getConnectedUserId()).isEqualTo(42L);

        verify(aiGatewayRoutingPolicyRepository).save(policy);
    }

    @Test
    void testUpdateConnectedUserIdClearsConnectedUserIdWhenArgumentIsNull() {
        AiGatewayRoutingPolicy policy = new AiGatewayRoutingPolicy("p", AiGatewayRoutingStrategyType.SIMPLE);

        policy.setConnectedUserId(42L);

        when(aiGatewayRoutingPolicyRepository.findById(5L)).thenReturn(Optional.of(policy));
        when(aiGatewayRoutingPolicyRepository.save(policy)).thenReturn(policy);

        aiGatewayRoutingPolicyService.updateConnectedUserId(5L, null);

        assertThat(policy.getConnectedUserId()).isNull();
    }

    /**
     * The automation-facing workspace scope must be untouched by the new connected-user scope: updating one column must
     * never disturb the other, since {@code ck_ai_gateway_routing_policy_workspace_connected_user_not_both} treats them
     * as mutually exclusive and a caller flipping between scopes relies on this method touching only its own column.
     */
    @Test
    void testUpdateConnectedUserIdDoesNotDisturbWorkspaceId() {
        AiGatewayRoutingPolicy policy = new AiGatewayRoutingPolicy("p", AiGatewayRoutingStrategyType.SIMPLE);

        policy.setWorkspaceId(7L);

        when(aiGatewayRoutingPolicyRepository.findById(5L)).thenReturn(Optional.of(policy));
        when(aiGatewayRoutingPolicyRepository.save(policy)).thenReturn(policy);

        aiGatewayRoutingPolicyService.updateConnectedUserId(5L, 42L);

        assertThat(policy.getWorkspaceId()).isEqualTo(7L);
    }
}

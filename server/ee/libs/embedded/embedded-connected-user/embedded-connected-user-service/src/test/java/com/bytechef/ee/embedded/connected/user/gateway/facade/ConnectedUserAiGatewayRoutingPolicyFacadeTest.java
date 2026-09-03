/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.connected.user.gateway.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayRoutingPolicyService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link ConnectedUserAiGatewayRoutingPolicyFacadeImpl}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class ConnectedUserAiGatewayRoutingPolicyFacadeTest {

    private static final long CONNECTED_USER_ID = 5L;
    private static final long POLICY_ID = 10L;
    private static final long OTHER_TENANT_POLICY_ID = 999L;
    private static final long OTHER_TENANT_CONNECTED_USER_ID = 888L;
    private static final long MISSING_CONNECTED_USER_ID = 777L;
    private static final long OTHER_CONNECTED_USER_ID = 6L;
    private static final long WORKSPACE_ID = 42L;

    @Mock
    private AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService;

    @Mock
    private ConnectedUserService connectedUserService;

    private ConnectedUserAiGatewayRoutingPolicyFacadeImpl connectedUserAiGatewayRoutingPolicyFacade;

    @BeforeEach
    void setUp() {
        connectedUserAiGatewayRoutingPolicyFacade = new ConnectedUserAiGatewayRoutingPolicyFacadeImpl(
            aiGatewayRoutingPolicyService, connectedUserService);
    }

    @Test
    void testBindRejectsAPolicyFromAnotherTenant() {
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(OTHER_TENANT_POLICY_ID))
            .thenThrow(new IllegalArgumentException("Routing policy not found: " + OTHER_TENANT_POLICY_ID));

        assertThatThrownBy(
            () -> connectedUserAiGatewayRoutingPolicyFacade.bind(OTHER_TENANT_POLICY_ID, CONNECTED_USER_ID))
                .isInstanceOf(IllegalArgumentException.class);

        verify(connectedUserService, never()).fetchConnectedUser(CONNECTED_USER_ID);
    }

    @Test
    void testBindRejectsAConnectedUserFromAnotherTenant() {
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(POLICY_ID)).thenReturn(policy(POLICY_ID));
        when(connectedUserService.fetchConnectedUser(OTHER_TENANT_CONNECTED_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
            () -> connectedUserAiGatewayRoutingPolicyFacade.bind(POLICY_ID, OTHER_TENANT_CONNECTED_USER_ID))
                .isInstanceOf(IllegalArgumentException.class);

        verify(aiGatewayRoutingPolicyService, never()).updateConnectedUserId(anyLong(), any());
    }

    /**
     * The requirement is not any particular exception type -- it is that a connected user id belonging to another
     * tenant is indistinguishable from one that plainly does not exist anywhere. Both cases reach
     * {@link ConnectedUserService#fetchConnectedUser(long)} the identical way -- structurally, per the reviewer's
     * finding, since {@code BaseDataSource}'s per-connection {@code search_path} makes a foreign row invisible rather
     * than rejected -- and must produce the identical {@link IllegalArgumentException} shape, not two different
     * messages an attacker could use as an enumeration oracle.
     */
    @Test
    void testConnectedUserNotFoundIsIndistinguishableBetweenCrossTenantAndGenuinelyMissing() {
        when(aiGatewayRoutingPolicyService.getRoutingPolicy(POLICY_ID)).thenReturn(policy(POLICY_ID));
        when(connectedUserService.fetchConnectedUser(OTHER_TENANT_CONNECTED_USER_ID)).thenReturn(Optional.empty());
        when(connectedUserService.fetchConnectedUser(MISSING_CONNECTED_USER_ID)).thenReturn(Optional.empty());

        Throwable crossTenantException = catchThrowableFromBind(POLICY_ID, OTHER_TENANT_CONNECTED_USER_ID);
        Throwable missingException = catchThrowableFromBind(POLICY_ID, MISSING_CONNECTED_USER_ID);

        assertThat(crossTenantException).isInstanceOf(IllegalArgumentException.class);
        assertThat(missingException).isInstanceOf(IllegalArgumentException.class);
        assertThat(crossTenantException.getClass()).isEqualTo(missingException.getClass());
        assertThat(crossTenantException.getMessage())
            .as("Same message template for both -- only the id differs, never the shape of the error")
            .isEqualTo("Connected user not found: " + OTHER_TENANT_CONNECTED_USER_ID);
        assertThat(missingException.getMessage()).isEqualTo("Connected user not found: " + MISSING_CONNECTED_USER_ID);
    }

    /**
     * Finding 1 from review round 1: a workspace-scoped policy must be rejected cleanly rather than reaching
     * {@code updateConnectedUserId}, where {@code ck_ai_gateway_routing_policy_workspace_connected_user_not_both} would
     * otherwise surface as an unmapped {@code DataIntegrityViolationException}. This test fails without the fix -- the
     * pre-fix {@code bind} called {@code updateConnectedUserId} unconditionally.
     */
    @Test
    void testBindRejectsAWorkspaceScopedPolicy() {
        AiGatewayRoutingPolicy workspacePolicy = policy(POLICY_ID);

        workspacePolicy.setWorkspaceId(WORKSPACE_ID);

        when(aiGatewayRoutingPolicyService.getRoutingPolicy(POLICY_ID)).thenReturn(workspacePolicy);

        assertThatThrownBy(() -> connectedUserAiGatewayRoutingPolicyFacade.bind(POLICY_ID, CONNECTED_USER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bound to a workspace");

        verify(connectedUserService, never()).fetchConnectedUser(anyLong());
        verify(aiGatewayRoutingPolicyService, never()).updateConnectedUserId(anyLong(), any());
    }

    /**
     * Finding 2 from review round 1: binding a policy that is already bound to a DIFFERENT connected user must be
     * rejected rather than silently moved -- the connected user who currently owns it is a bystander the caller never
     * named. This test fails without the fix -- the pre-fix {@code bind} inspected only the target connected user's
     * existing binding, never the policy's own {@code connectedUserId}, so this call would have silently taken the
     * policy away from {@code OTHER_CONNECTED_USER_ID}.
     */
    @Test
    void testBindRejectsAPolicyAlreadyBoundToADifferentConnectedUser() {
        AiGatewayRoutingPolicy policy = policy(POLICY_ID);

        policy.setConnectedUserId(OTHER_CONNECTED_USER_ID);

        when(aiGatewayRoutingPolicyService.getRoutingPolicy(POLICY_ID)).thenReturn(policy);
        when(connectedUserService.fetchConnectedUser(CONNECTED_USER_ID)).thenReturn(Optional.of(connectedUser()));

        assertThatThrownBy(() -> connectedUserAiGatewayRoutingPolicyFacade.bind(POLICY_ID, CONNECTED_USER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already bound to connected user " + OTHER_CONNECTED_USER_ID);

        verify(aiGatewayRoutingPolicyService, never()).updateConnectedUserId(anyLong(), any());
    }

    /**
     * Rebinding a policy to the SAME connected user it is already bound to must not trip the "bound to a different
     * connected user" guard -- this is a harmless no-op, not a bystander-affecting move.
     */
    @Test
    void testRebindingAPolicyToItsOwnConnectedUserDoesNotThrow() {
        AiGatewayRoutingPolicy policy = policy(POLICY_ID);

        policy.setConnectedUserId(CONNECTED_USER_ID);

        when(aiGatewayRoutingPolicyService.getRoutingPolicy(POLICY_ID)).thenReturn(policy);
        when(connectedUserService.fetchConnectedUser(CONNECTED_USER_ID)).thenReturn(Optional.of(connectedUser()));
        when(aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(Optional.of(policy));

        connectedUserAiGatewayRoutingPolicyFacade.bind(POLICY_ID, CONNECTED_USER_ID);

        verify(aiGatewayRoutingPolicyService).updateConnectedUserId(POLICY_ID, CONNECTED_USER_ID);
    }

    @Test
    void testBindSetsConnectedUserIdOnTheTargetPolicy() {
        AiGatewayRoutingPolicy policy = policy(POLICY_ID);

        when(aiGatewayRoutingPolicyService.getRoutingPolicy(POLICY_ID)).thenReturn(policy);
        when(connectedUserService.fetchConnectedUser(CONNECTED_USER_ID)).thenReturn(Optional.of(connectedUser()));
        when(aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(Optional.empty());

        connectedUserAiGatewayRoutingPolicyFacade.bind(POLICY_ID, CONNECTED_USER_ID);

        verify(aiGatewayRoutingPolicyService).updateConnectedUserId(POLICY_ID, CONNECTED_USER_ID);
    }

    /**
     * The ⚑ design decision this task required: binding a connected user who already has a different policy bound
     * replaces the old binding rather than letting the partial unique index reject the write. Reassigning a customer to
     * a different plan is a routine admin action and should not require an explicit unbind first. This is the mirror of
     * {@link #testBindRejectsAPolicyAlreadyBoundToADifferentConnectedUser()}: here the party who loses their binding is
     * the connected user the caller named, not a bystander, so replacing is correct.
     */
    @Test
    void testBindingADifferentPolicyReplacesTheExistingBinding() {
        AiGatewayRoutingPolicy oldPolicy = policy(1L);
        AiGatewayRoutingPolicy newPolicy = policy(POLICY_ID);

        when(aiGatewayRoutingPolicyService.getRoutingPolicy(POLICY_ID)).thenReturn(newPolicy);
        when(connectedUserService.fetchConnectedUser(CONNECTED_USER_ID)).thenReturn(Optional.of(connectedUser()));
        when(aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(Optional.of(oldPolicy));

        connectedUserAiGatewayRoutingPolicyFacade.bind(POLICY_ID, CONNECTED_USER_ID);

        verify(aiGatewayRoutingPolicyService).updateConnectedUserId(1L, null);
        verify(aiGatewayRoutingPolicyService).updateConnectedUserId(POLICY_ID, CONNECTED_USER_ID);
    }

    @Test
    void testRebindingTheSamePolicyDoesNotUnbindItFirst() {
        AiGatewayRoutingPolicy policy = policy(POLICY_ID);

        when(aiGatewayRoutingPolicyService.getRoutingPolicy(POLICY_ID)).thenReturn(policy);
        when(connectedUserService.fetchConnectedUser(CONNECTED_USER_ID)).thenReturn(Optional.of(connectedUser()));
        when(aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(Optional.of(policy));

        connectedUserAiGatewayRoutingPolicyFacade.bind(POLICY_ID, CONNECTED_USER_ID);

        verify(aiGatewayRoutingPolicyService, never()).updateConnectedUserId(POLICY_ID, null);
        verify(aiGatewayRoutingPolicyService).updateConnectedUserId(POLICY_ID, CONNECTED_USER_ID);
    }

    @Test
    void testUnbindClearsTheBoundPolicy() {
        AiGatewayRoutingPolicy policy = policy(POLICY_ID);

        when(aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(Optional.of(policy));

        connectedUserAiGatewayRoutingPolicyFacade.unbind(CONNECTED_USER_ID);

        verify(aiGatewayRoutingPolicyService).updateConnectedUserId(POLICY_ID, null);
    }

    @Test
    void testUnbindWithNoBoundPolicyIsANoOp() {
        when(aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(CONNECTED_USER_ID))
            .thenReturn(Optional.empty());

        connectedUserAiGatewayRoutingPolicyFacade.unbind(CONNECTED_USER_ID);

        verify(aiGatewayRoutingPolicyService, never()).updateConnectedUserId(anyLong(), any());
    }

    private Throwable catchThrowableFromBind(long routingPolicyId, long connectedUserId) {
        try {
            connectedUserAiGatewayRoutingPolicyFacade.bind(routingPolicyId, connectedUserId);
        } catch (RuntimeException exception) {
            return exception;
        }

        throw new AssertionError("Expected bind(" + routingPolicyId + ", " + connectedUserId + ") to throw");
    }

    private static AiGatewayRoutingPolicy policy(long id) {
        AiGatewayRoutingPolicy policy = new AiGatewayRoutingPolicy("p", AiGatewayRoutingStrategyType.SIMPLE);

        ReflectionTestUtils.setField(policy, "id", id);

        return policy;
    }

    private static ConnectedUser connectedUser() {
        return new ConnectedUser();
    }
}

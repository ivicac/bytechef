/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.ee.automation.ai.gateway.config.AiGatewayIntTestConfiguration;
import com.bytechef.ee.automation.ai.gateway.service.AiGatewayIntTestConfigurationSharedMocks;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.gateway.facade.ConnectedUserAiGatewayRoutingPolicyFacade;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayRoutingPolicyService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end coverage for connected-user AI Gateway routing-policy binding (embedded phase 2, task 3) against a real
 * Postgres database rather than mocks -- in particular, that the partial unique index
 * {@code uk_ai_gateway_routing_policy_connected_user_id} never actually fires for a normal rebind, because
 * {@link ConnectedUserAiGatewayRoutingPolicyFacade#bind} clears the connected user's previous binding first. A
 * mock-based test can assert the facade calls the right methods in the right order; only a real database proves the
 * constraint the whole design exists to satisfy is never violated by that order.
 *
 * <p>
 * {@link #testDeletingAConnectedUserUnbindsButKeepsThePolicy()} is the delete-cascade proof the task requires: the
 * listener only fires through Spring Data JDBC's actual relational event publication on a real delete, which mocking
 * {@link ConnectedUserService} cannot exercise.
 *
 * @version ee
 */
@ActiveProfiles("testint")
@SpringBootTest(classes = AiGatewayIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
@AiGatewayIntTestConfigurationSharedMocks
class ConnectedUserAiGatewayRoutingPolicyBindingIntTest {

    @Autowired
    private AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService;

    @Autowired
    private ConnectedUserAiGatewayRoutingPolicyFacade connectedUserAiGatewayRoutingPolicyFacade;

    @Autowired
    private ConnectedUserService connectedUserService;

    @Test
    void testBindingADifferentPolicyReplacesTheExistingBindingWithoutViolatingTheUniqueIndex() {
        long environmentId = Environment.DEVELOPMENT.ordinal();

        AiGatewayRoutingPolicy firstPolicy = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("rebind-first-policy", AiGatewayRoutingStrategyType.SIMPLE));
        AiGatewayRoutingPolicy secondPolicy = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("rebind-second-policy", AiGatewayRoutingStrategyType.SIMPLE));

        ConnectedUser connectedUser = connectedUserService.createConnectedUser("rebind-user", environmentId);

        connectedUserAiGatewayRoutingPolicyFacade.bind(firstPolicy.getId(), connectedUser.getId());

        // The real assertion: this second bind, to a DIFFERENT policy for the SAME connected user, must not throw --
        // if the facade wrote the new binding before clearing the old one, the partial unique index would reject it
        // right here.
        connectedUserAiGatewayRoutingPolicyFacade.bind(secondPolicy.getId(), connectedUser.getId());

        AiGatewayRoutingPolicy reloadedFirstPolicy = aiGatewayRoutingPolicyService.getRoutingPolicy(
            firstPolicy.getId());
        AiGatewayRoutingPolicy reloadedSecondPolicy = aiGatewayRoutingPolicyService.getRoutingPolicy(
            secondPolicy.getId());

        assertThat(reloadedFirstPolicy.getConnectedUserId())
            .as("The old policy must be unbound, not left dangling on a connected user that moved on")
            .isNull();
        assertThat(reloadedSecondPolicy.getConnectedUserId()).isEqualTo(connectedUser.getId());
    }

    /**
     * Finding 1 from review round 1: binding a policy that is already bound to a WORKSPACE must be rejected cleanly.
     * Against a real database (unlike a mocked repository), the pre-fix code would have reached
     * {@code updateConnectedUserId}, which leaves {@code workspace_id} alone, and Postgres would have rejected the
     * write with {@code ck_ai_gateway_routing_policy_workspace_connected_user_not_both} -- an unmapped
     * {@code DataIntegrityViolationException} instead of this clean {@link IllegalArgumentException}. This test would
     * fail on exception TYPE without the fix, not merely fail to throw.
     */
    @Test
    void testBindingAWorkspaceScopedPolicyIsRejectedCleanlyAgainstARealDatabase() {
        long workspaceId = 909_777L;
        long environmentId = Environment.DEVELOPMENT.ordinal();

        AiGatewayRoutingPolicy workspacePolicy = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("workspace-scoped-cannot-bind", AiGatewayRoutingStrategyType.SIMPLE));

        aiGatewayRoutingPolicyService.updateWorkspaceId(workspacePolicy.getId(), workspaceId);

        ConnectedUser connectedUser = connectedUserService.createConnectedUser(
            "workspace-scoped-cannot-bind-user", environmentId);

        assertThatThrownBy(
            () -> connectedUserAiGatewayRoutingPolicyFacade.bind(workspacePolicy.getId(), connectedUser.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bound to a workspace");

        AiGatewayRoutingPolicy reloadedPolicy = aiGatewayRoutingPolicyService.getRoutingPolicy(
            workspacePolicy.getId());

        assertThat(reloadedPolicy.getWorkspaceId()).isEqualTo(workspaceId);
        assertThat(reloadedPolicy.getConnectedUserId()).isNull();
    }

    /**
     * Finding 2 from review round 1: binding a policy that already belongs to a DIFFERENT connected user must be
     * rejected, not silently moved -- the connected user who currently owns it never appears in this call and must not
     * lose their routing as a side effect. Against a real database, this also proves the bystander's binding survives
     * untouched, not merely that an exception was thrown.
     */
    @Test
    void testBindingAPolicyAlreadyBoundToAnotherConnectedUserIsRejectedAndLeavesTheBystanderUntouched() {
        long environmentId = Environment.PRODUCTION.ordinal();

        AiGatewayRoutingPolicy policy = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("bystander-policy", AiGatewayRoutingStrategyType.SIMPLE));

        ConnectedUser bystander = connectedUserService.createConnectedUser(
            "bystander-connected-user", environmentId);
        ConnectedUser interloper = connectedUserService.createConnectedUser(
            "interloper-connected-user", environmentId);

        connectedUserAiGatewayRoutingPolicyFacade.bind(policy.getId(), bystander.getId());

        assertThatThrownBy(() -> connectedUserAiGatewayRoutingPolicyFacade.bind(policy.getId(), interloper.getId()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already bound to connected user " + bystander.getId());

        AiGatewayRoutingPolicy reloadedPolicy = aiGatewayRoutingPolicyService.getRoutingPolicy(policy.getId());

        assertThat(reloadedPolicy.getConnectedUserId())
            .as("The bystander must keep their routing -- the rejected bind must not have moved it")
            .isEqualTo(bystander.getId());
    }

    /**
     * The global constraint every phase-2 task carries: automation behaviour must not change. A workspace-scoped policy
     * (what an automation caller creates and reads) must be unaffected by connected-user binding activity elsewhere in
     * the same table.
     */
    @Test
    void testBindingAConnectedUserPolicyDoesNotAffectAWorkspaceScopedPolicy() {
        long workspaceId = 909_555L;
        long environmentId = Environment.STAGING.ordinal();

        AiGatewayRoutingPolicy workspacePolicy = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("automation-workspace-policy", AiGatewayRoutingStrategyType.SIMPLE));

        aiGatewayRoutingPolicyService.updateWorkspaceId(workspacePolicy.getId(), workspaceId);

        AiGatewayRoutingPolicy connectedUserPolicy = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy(
                "automation-untouched-connected-user-policy", AiGatewayRoutingStrategyType.SIMPLE));

        ConnectedUser connectedUser = connectedUserService.createConnectedUser(
            "automation-untouched-user", environmentId);

        connectedUserAiGatewayRoutingPolicyFacade.bind(connectedUserPolicy.getId(), connectedUser.getId());

        assertThat(aiGatewayRoutingPolicyService.getRoutingPoliciesByWorkspaceId(workspaceId))
            .as("Binding a connected-user policy must never leak into or disturb a workspace-scoped query")
            .extracting(AiGatewayRoutingPolicy::getId)
            .containsExactly(workspacePolicy.getId());

        AiGatewayRoutingPolicy reloadedWorkspacePolicy = aiGatewayRoutingPolicyService.getRoutingPolicy(
            workspacePolicy.getId());

        assertThat(reloadedWorkspacePolicy.getWorkspaceId()).isEqualTo(workspaceId);
        assertThat(reloadedWorkspacePolicy.getConnectedUserId()).isNull();
    }

    @Test
    void testDeletingAConnectedUserUnbindsButKeepsThePolicy() {
        long environmentId = Environment.PRODUCTION.ordinal();

        AiGatewayRoutingPolicy policy = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("delete-cascade-policy", AiGatewayRoutingStrategyType.SIMPLE));

        ConnectedUser connectedUser = connectedUserService.createConnectedUser("delete-cascade-user", environmentId);

        connectedUserAiGatewayRoutingPolicyFacade.bind(policy.getId(), connectedUser.getId());

        connectedUserService.deleteConnectedUser(connectedUser.getId());

        AiGatewayRoutingPolicy reloadedPolicy = aiGatewayRoutingPolicyService.getRoutingPolicy(policy.getId());

        assertThat(reloadedPolicy)
            .as("The listener must unbind the policy, never delete it -- it is a vendor-owned object that may be "
                + "re-bound to a different connected user later")
            .isNotNull();
        assertThat(reloadedPolicy.getConnectedUserId()).isNull();
    }
}

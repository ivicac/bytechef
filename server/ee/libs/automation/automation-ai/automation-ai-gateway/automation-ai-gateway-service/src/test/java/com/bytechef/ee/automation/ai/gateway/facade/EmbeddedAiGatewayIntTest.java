/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.automation.ai.gateway.config.AiGatewayIntTestConfiguration;
import com.bytechef.ee.automation.ai.gateway.service.AiGatewayIntTestConfigurationSharedMocks;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayEmbeddedSettings;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingPolicy;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayEmbeddedSettingsService;
import com.bytechef.ee.platform.ai.gateway.service.AiGatewayRoutingPolicyService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.domain.Property;
import com.bytechef.platform.configuration.service.PropertyService;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end coverage for the embedded AI Gateway default-routing chain: a connected user id, resolved the way an
 * embedded controller resolves one, feeds {@link AiGatewayFacadeImpl#resolveEmbeddedDefaultRoutingPolicyId} against a
 * real Postgres database, rather than mocks. Each test method lives in the same package as {@link AiGatewayFacadeImpl}
 * to reach its package-private {@code resolveEmbeddedDefaultRoutingPolicyId} method directly — the call site spec §5
 * places at request entry, ahead of the full chat-completion pipeline this test deliberately does not drive. The facade
 * itself no longer resolves an external id to a connected user id (that responsibility moved to the embedded
 * controller); this test constructs the connected user directly via {@link ConnectedUserService} and passes its
 * already-resolved id straight to the facade.
 *
 * <p>
 * The identity-resolution guard the old {@code ConnectedUserResolver}-era test pinned — that resolving an unrecognised
 * or wrong-environment external id creates no phantom {@link ConnectedUser} row — moved with that responsibility, and
 * is proven here directly against {@link ConnectedUserService#fetchConnectedUser}, the exact call
 * {@code EmbeddedAiGatewayChatCompletionApiController.resolveConnectedUserId} makes, with a row count taken before and
 * after against this same real Postgres database rather than against mocks (see
 * {@code testFetchConnectedUserForDifferentEnvironmentDoesNotResolveAndCreatesNoConnectedUserRow} and
 * {@code testFetchConnectedUserForUnknownExternalIdCreatesNoConnectedUserRow}).
 *
 * <p>
 * {@link PropertyService} stays a {@link AiGatewayIntTestConfigurationSharedMocks Mockito mock}, as it already is for
 * every other test in this module — the embedded settings row is produced by round-tripping through the real
 * {@link AiGatewayEmbeddedSettingsService#upsert} write path and capturing what it would have persisted, then handing
 * that same value back out of a stubbed {@link PropertyService#fetchProperty}. That exercises the service's own
 * encode/decode contract without pulling {@code platform-configuration-service} (and the credential-store beans it
 * requires) onto this module's test classpath.
 *
 * @version ee
 */
@ActiveProfiles("testint")
@SpringBootTest(classes = AiGatewayIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
@AiGatewayIntTestConfigurationSharedMocks
public class EmbeddedAiGatewayIntTest {

    @Autowired
    private AiGatewayEmbeddedSettingsService aiGatewayEmbeddedSettingsService;

    @Autowired
    private AiGatewayFacadeImpl aiGatewayFacadeImpl;

    @Autowired
    private AiGatewayRoutingPolicyService aiGatewayRoutingPolicyService;

    @Autowired
    private ConnectedUserService connectedUserService;

    @Autowired
    private PropertyService propertyService;

    @Test
    void testEmbeddedDefaultRoutingPolicyIsResolvedForAKnownConnectedUser() {
        long environmentId = Environment.DEVELOPMENT.ordinal();

        AiGatewayRoutingPolicy defaultPolicy = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("embedded-default-known-user", AiGatewayRoutingStrategyType.SIMPLE));

        ConnectedUser connectedUser = connectedUserService.createConnectedUser("known-external-user", environmentId);

        stubEmbeddedSettings(environmentId, defaultPolicy.getId());

        Long routingPolicyId = aiGatewayFacadeImpl.resolveEmbeddedDefaultRoutingPolicyId(
            connectedUser.getId(), environmentId);

        assertThat(routingPolicyId).isEqualTo(defaultPolicy.getId());
    }

    @Test
    void testResolveEmbeddedDefaultRoutingPolicyIdFallsThroughToNullWithNoEmbeddedSettingsRow() {
        long environmentId = Environment.STAGING.ordinal();

        AiGatewayRoutingPolicy defaultPolicy = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("embedded-default-no-settings-row", AiGatewayRoutingStrategyType.SIMPLE));

        ConnectedUser connectedUser =
            connectedUserService.createConnectedUser("no-settings-row-user", environmentId);

        // No upsert for this environmentId, so the mocked PropertyService's unstubbed fetchProperty answers
        // Optional.empty() (Mockito's default for Optional-returning methods) — there is no embedded settings row.
        Long routingPolicyId = aiGatewayFacadeImpl.resolveEmbeddedDefaultRoutingPolicyId(
            connectedUser.getId(), environmentId);

        assertThat(routingPolicyId)
            .as("With no embedded settings row for this environment, the embedded-default step must resolve " +
                "nothing rather than guessing — this is the request falling through, per Phase 1 scope, with " +
                "nothing further in the chain to try today")
            .isNull();

        // The pre-existing null-workspace policy is still the correct fall-through target for a future caller,
        // even though nothing in production code queries it yet (Phase 2 scope) — see the null-scope assertions
        // below for the read path proven against a real database.
        assertThat(aiGatewayRoutingPolicyService.getDefaultRoutingPolicies())
            .extracting(AiGatewayRoutingPolicy::getId)
            .contains(defaultPolicy.getId());
    }

    @Test
    void testDefaultRoutingPolicyWithNullWorkspaceIsReturnedByDefaultQueryOnlyNotByWorkspaceScopedQuery() {
        long workspaceId = 909_001L;

        AiGatewayRoutingPolicy defaultPolicy = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("null-scope-policy", AiGatewayRoutingStrategyType.SIMPLE));

        AiGatewayRoutingPolicy workspacePolicy = aiGatewayRoutingPolicyService.create(
            new AiGatewayRoutingPolicy("workspace-scoped-policy", AiGatewayRoutingStrategyType.SIMPLE));

        aiGatewayRoutingPolicyService.updateWorkspaceId(workspacePolicy.getId(), workspaceId);

        assertThat(aiGatewayRoutingPolicyService.getDefaultRoutingPolicies())
            .as("getDefaultRoutingPolicies() must surface the null-workspace policy")
            .extracting(AiGatewayRoutingPolicy::getId)
            .contains(defaultPolicy.getId());
        assertThat(aiGatewayRoutingPolicyService.getDefaultRoutingPolicies())
            .as("getDefaultRoutingPolicies() must never surface a workspace-bound policy")
            .extracting(AiGatewayRoutingPolicy::getId)
            .doesNotContain(workspacePolicy.getId());

        assertThat(aiGatewayRoutingPolicyService.getRoutingPoliciesByWorkspaceId(workspaceId))
            .as("getRoutingPoliciesByWorkspaceId(...) must surface the workspace-bound policy")
            .extracting(AiGatewayRoutingPolicy::getId)
            .contains(workspacePolicy.getId());
        assertThat(aiGatewayRoutingPolicyService.getRoutingPoliciesByWorkspaceId(workspaceId))
            .as("getRoutingPoliciesByWorkspaceId(...) must never leak the null-workspace policy — Spring Data's " +
                "derived findAllByWorkspaceId query, exercised here against a real database rather than a mock")
            .extracting(AiGatewayRoutingPolicy::getId)
            .doesNotContain(defaultPolicy.getId());
    }

    @Test
    void testFetchConnectedUserForDifferentEnvironmentDoesNotResolveAndCreatesNoConnectedUserRow() {
        long developmentEnvironmentId = Environment.DEVELOPMENT.ordinal();
        long stagingEnvironmentId = Environment.STAGING.ordinal();

        connectedUserService.createConnectedUser("cross-environment-user", developmentEnvironmentId);

        long connectedUserRowCountBeforeFetch = countConnectedUsers(Environment.STAGING);

        // This is the exact call EmbeddedAiGatewayChatCompletionApiController.resolveConnectedUserId makes —
        // fetchConnectedUser, never getConnectedUser (which throws) and never createConnectedUser — proven here
        // against a real database rather than a mock, the way the SPI-era test this replaces once did.
        Optional<ConnectedUser> resolvedInWrongEnvironment = connectedUserService.fetchConnectedUser(
            "cross-environment-user", stagingEnvironmentId);

        assertThat(resolvedInWrongEnvironment)
            .as("A connected user created in one environment must not resolve when fetched for a different " +
                "environment")
            .isEmpty();

        long connectedUserRowCountAfterFetch = countConnectedUsers(Environment.STAGING);

        assertThat(connectedUserRowCountAfterFetch)
            .as("Resolving an id for the wrong environment must never create a phantom ConnectedUser row — the " +
                "exact bug ConnectedUserConstants.FRONTEND_RESERVED_PATH_SEGMENTS' retired 'external' literal " +
                "guards against — and only a row count against a real database proves it")
            .isEqualTo(connectedUserRowCountBeforeFetch);
    }

    @Test
    void testFetchConnectedUserForUnknownExternalIdCreatesNoConnectedUserRow() {
        long environmentId = Environment.PRODUCTION.ordinal();

        long connectedUserRowCountBeforeFetch = countConnectedUsers(Environment.PRODUCTION);

        Optional<ConnectedUser> resolvedUnknownUser = connectedUserService.fetchConnectedUser(
            "unrecognised-external-user", environmentId);

        assertThat(resolvedUnknownUser)
            .as("An unrecognised external id must resolve to nothing rather than minting a phantom connected user")
            .isEmpty();

        long connectedUserRowCountAfterFetch = countConnectedUsers(Environment.PRODUCTION);

        assertThat(connectedUserRowCountAfterFetch)
            .as("Resolving an unrecognised external id must never create a ConnectedUser row — only a row count " +
                "against a real database proves it")
            .isEqualTo(connectedUserRowCountBeforeFetch);
    }

    private long countConnectedUsers(Environment environment) {
        Page<ConnectedUser> connectedUserPage = connectedUserService.getConnectedUsers(
            environment, null, null, null, null, 0);

        return connectedUserPage.getTotalElements();
    }

    @SuppressWarnings("unchecked")
    private void stubEmbeddedSettings(long environmentId, long defaultRoutingPolicyId) {
        aiGatewayEmbeddedSettingsService.upsert(
            new AiGatewayEmbeddedSettings(
                environmentId, null, null, null, null, null, defaultRoutingPolicyId, null, null));

        ArgumentCaptor<Map<String, Object>> valueCaptor = ArgumentCaptor.forClass(Map.class);

        verify(propertyService).save(
            eq(AiGatewayEmbeddedSettings.PROPERTY_KEY), valueCaptor.capture(), eq(Property.Scope.EMBEDDED),
            isNull(), eq(environmentId));

        Property property = new Property();

        property.setValue(valueCaptor.getValue());
        property.setEnvironment((int) environmentId);

        when(
            propertyService.fetchProperty(
                AiGatewayEmbeddedSettings.PROPERTY_KEY, Property.Scope.EMBEDDED, null, environmentId))
                    .thenReturn(Optional.of(property));
    }
}

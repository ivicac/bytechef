/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Asserts the connected-user scoping added to {@code ai_gateway_routing_policy} and {@code ai_gateway_provider} against
 * the real database rather than against the changelog text: the check constraints
 * {@code ck_ai_gateway_routing_policy_workspace_connected_user_not_both} /
 * {@code ck_ai_gateway_provider_workspace_connected_user_not_both}, the routing-policy partial unique index
 * {@code uk_ai_gateway_routing_policy_connected_user_id} (single-column: a connected user has at most one policy), and
 * the provider partial unique index {@code uk_ai_gateway_provider_connected_user_id_type} (composite: a connected user
 * may legitimately hold one BYOK provider per provider type) — all added by
 * {@code platform/ai/gateway/00000000000001_ai_gateway_init.xml} (changelog is branch-only/unreleased, so all live in
 * the init changeset itself rather than a follow-up one).
 *
 * <p>
 * Rows are written with raw SQL on purpose, mirroring {@code KnowledgeBaseNameUniqueIndexIntTest} and
 * {@code DataTableNameUniqueIndexIntTest} -- the point is what the database refuses, not what the domain layer declines
 * to attempt.
 *
 * @version ee
 */
@SpringBootTest(classes = AiGatewayScopingIntTestConfiguration.class)
@TestPropertySource(properties = "spring.liquibase.contexts=configuration")
class AiGatewayScopingIntTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testWorkspaceAndConnectedUserBothSetIsRejectedByCheckConstraint() {
        assertThatThrownBy(() -> insertRoutingPolicy("scoping-both-set", 1L, 1L))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void testSecondPolicyForSameConnectedUserIsRejectedByPartialUniqueIndex() {
        insertRoutingPolicy("scoping-connected-user-first", null, 100L);

        assertThatThrownBy(() -> insertRoutingPolicy("scoping-connected-user-second", null, 100L))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Two connected users each getting their own policy must not be over-constrained by the partial index. Because
     * {@code ConnectedUser} rows are already per-environment, this also covers "the same external customer in two
     * environments" -- that case is two different connected user ids, indistinguishable from any other pair of distinct
     * connected users here.
     */
    @Test
    void testTwoPoliciesForDifferentConnectedUsersBothInsert() {
        assertThatCode(() -> {
            insertRoutingPolicy("scoping-connected-user-a", null, 200L);
            insertRoutingPolicy("scoping-connected-user-b", null, 201L);
        }).doesNotThrowAnyException();
    }

    /**
     * Automation traffic has no connected user at all: an existing row with both {@code workspace_id} and
     * {@code connected_user_id} null -- the default tier -- must keep inserting cleanly. Neither the check constraint
     * (permits both null) nor the partial unique index (scoped to {@code connected_user_id IS NOT NULL}) may reject it.
     */
    @Test
    void testRowWithBothScopeColumnsNullStillInserts() {
        assertThatCode(() -> insertRoutingPolicy("scoping-default-tier", null, null))
            .doesNotThrowAnyException();
    }

    private void insertRoutingPolicy(String name, Long workspaceId, Long connectedUserId) {
        jdbcTemplate.update(
            "INSERT INTO ai_gateway_routing_policy (name, strategy, workspace_id, connected_user_id, enabled, " +
                "created_date, last_modified_date, version) VALUES (?, ?, ?, ?, true, now(), now(), 0)",
            new Object[] {
                name, AiGatewayRoutingStrategyType.PRIORITY_FALLBACK.ordinal(), workspaceId, connectedUserId
            },
            new int[] {
                Types.VARCHAR, Types.INTEGER, Types.BIGINT, Types.BIGINT
            });
    }

    @Test
    void testProviderWorkspaceAndConnectedUserBothSetIsRejectedByCheckConstraint() {
        assertThatThrownBy(() -> insertProvider("scoping-both-set", AiGatewayProviderType.OPENAI, 1L, 1L))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The composite (not single-column) partial unique index this task adds: a connected user with an OpenAI provider
     * already on file cannot get a second OpenAI provider row — without this,
     * {@code AiGatewayProviderRepository#findByConnectedUserIdAndType} would have no way to pick a winner
     * deterministically, and "which of a customer's API keys gets used and billed" is not a place non-determinism
     * belongs.
     */
    @Test
    void testSecondProviderForSameConnectedUserAndTypeIsRejectedByPartialUniqueIndex() {
        insertProvider("scoping-connected-user-openai-first", AiGatewayProviderType.OPENAI, null, 100L);

        assertThatThrownBy(
            () -> insertProvider("scoping-connected-user-openai-second", AiGatewayProviderType.OPENAI, null, 100L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The index is on {@code (connected_user_id, type)}, not {@code connected_user_id} alone — a customer legitimately
     * holding both their own OpenAI and their own Anthropic credentials must not be over-constrained by it.
     */
    @Test
    void testTwoProvidersForSameConnectedUserButDifferentTypesBothInsert() {
        assertThatCode(() -> {
            insertProvider("scoping-connected-user-openai", AiGatewayProviderType.OPENAI, null, 101L);
            insertProvider("scoping-connected-user-anthropic", AiGatewayProviderType.ANTHROPIC, null, 101L);
        }).doesNotThrowAnyException();
    }

    /**
     * Two different connected users each getting their own same-type provider must not be over-constrained either — the
     * index is scoped per connected user, not global per type.
     */
    @Test
    void testTwoProvidersForDifferentConnectedUsersSameTypeBothInsert() {
        assertThatCode(() -> {
            insertProvider("scoping-connected-user-a-openai", AiGatewayProviderType.OPENAI, null, 200L);
            insertProvider("scoping-connected-user-b-openai", AiGatewayProviderType.OPENAI, null, 201L);
        }).doesNotThrowAnyException();
    }

    /**
     * Automation/tenant-shared providers have no connected user at all: an existing row with both {@code workspace_id}
     * and {@code connected_user_id} null -- the default tier -- must keep inserting cleanly, including a second
     * provider of the same type as an existing tenant-scoped one (the partial index is scoped to
     * {@code connected_user_id IS NOT NULL} and never applies here).
     */
    @Test
    void testProviderRowWithBothScopeColumnsNullStillInserts() {
        assertThatCode(() -> {
            insertProvider("scoping-default-tier-openai-a", AiGatewayProviderType.OPENAI, null, null);
            insertProvider("scoping-default-tier-openai-b", AiGatewayProviderType.OPENAI, null, null);
        }).doesNotThrowAnyException();
    }

    private void insertProvider(String name, AiGatewayProviderType type, Long workspaceId, Long connectedUserId) {
        jdbcTemplate.update(
            "INSERT INTO ai_gateway_provider (name, type, api_key, workspace_id, connected_user_id, enabled, " +
                "created_by, created_date, last_modified_by, last_modified_date, version) " +
                "VALUES (?, ?, 'sk-test', ?, ?, true, 'test', now(), 'test', now(), 0)",
            new Object[] {
                name, type.ordinal(), workspaceId, connectedUserId
            },
            new int[] {
                Types.VARCHAR, Types.INTEGER, Types.BIGINT, Types.BIGINT
            });
    }
}

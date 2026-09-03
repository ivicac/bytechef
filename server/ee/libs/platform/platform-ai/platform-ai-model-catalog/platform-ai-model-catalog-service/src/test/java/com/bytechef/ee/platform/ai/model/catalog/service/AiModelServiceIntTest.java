/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.model.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.platform.ai.model.catalog.config.AiModelCatalogIntTestConfiguration;
import com.bytechef.ee.platform.ai.model.catalog.domain.AiModel;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Proves the renamed {@code ai_model} table is created from scratch by the module's own changelog against a real
 * PostgreSQL via Testcontainers — the {@code liquibase} Spring profile applies nothing via {@code bootRun}, so a
 * fresh-schema integration test is the real evidence. Deliberately runs WITHOUT {@code bytechef.ai.gateway.enabled},
 * pinning that the catalog registers independently of the gateway toggle.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ActiveProfiles("testint")
@SpringBootTest(classes = AiModelCatalogIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
public class AiModelServiceIntTest {

    @Autowired
    private AiModelService aiModelService;

    @Test
    void testCreateAndFetchModelAgainstFreshSchema() {
        AiModel model = new AiModel(1L, "gpt-5");

        model.setContextWindow(400000);
        model.setInputCostPerMTokens(new BigDecimal("1.2500"));

        AiModel savedModel = aiModelService.create(model);

        assertThat(savedModel.getId()).isNotNull();

        AiModel fetchedModel = aiModelService.getModel(1L, "gpt-5");

        assertThat(fetchedModel.getContextWindow()).isEqualTo(400000);
        assertThat(aiModelService.findByModelIdentifier("gpt-5")).isPresent();
    }

    @Test
    void testDeleteRemovesRow() {
        AiModel savedModel = aiModelService.create(new AiModel(2L, "claude-fable-5"));

        aiModelService.delete(savedModel.getId());

        assertThat(aiModelService.findByModelIdentifier("claude-fable-5")).isEmpty();
    }

    /**
     * Final whole-branch review, I-4 — {@code applyAndSave} used to copy seven fields and silently drop
     * {@code defaultRoutingPolicyId}, so {@code update()} returned 200 while leaving the persisted value unchanged. The
     * field is settable once at creation (the GraphQL {@code createWorkspaceAiModel} mutation accepts it) and was then
     * permanently unchangeable. This proves BOTH halves now work: an update carrying a new id changes the persisted
     * value, and a later update carrying {@code null} clears it — the correct reading of a mutation that accepts the
     * field at all.
     */
    @Test
    void testUpdateChangesAndClearsDefaultRoutingPolicyId() {
        AiModel model = new AiModel(3L, "gpt-5-routing-policy-test");

        AiModel savedModel = aiModelService.create(model);

        AiModel updateWithPolicy = new AiModel(3L, "gpt-5-routing-policy-test");

        ReflectionTestUtils.setField(updateWithPolicy, "id", savedModel.getId());
        updateWithPolicy.setDefaultRoutingPolicyId(42L);

        AiModel updatedModel = aiModelService.update(updateWithPolicy);

        assertThat(updatedModel.getDefaultRoutingPolicyId()).isEqualTo(42L);

        AiModel refetchedModel = aiModelService.getModel(savedModel.getId());

        assertThat(refetchedModel.getDefaultRoutingPolicyId()).isEqualTo(42L);

        AiModel updateClearingPolicy = new AiModel(3L, "gpt-5-routing-policy-test");

        ReflectionTestUtils.setField(updateClearingPolicy, "id", savedModel.getId());
        updateClearingPolicy.setDefaultRoutingPolicyId(null);

        AiModel clearedModel = aiModelService.update(updateClearingPolicy);

        assertThat(clearedModel.getDefaultRoutingPolicyId()).isNull();

        AiModel refetchedClearedModel = aiModelService.getModel(savedModel.getId());

        assertThat(refetchedClearedModel.getDefaultRoutingPolicyId()).isNull();
    }
}

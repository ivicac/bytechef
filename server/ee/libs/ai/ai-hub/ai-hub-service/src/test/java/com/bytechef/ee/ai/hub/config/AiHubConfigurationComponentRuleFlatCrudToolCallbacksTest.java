/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.ee.automation.ai.tool.componentrule.ComponentRuleToolCallbacksFactory;
import com.bytechef.ee.platform.component.rule.ComponentRuleService;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Pins the three read names {@link AiHubConfiguration#componentRuleFlatCrudToolCallbacks} registers flat on both hub
 * agents, and the single mutation name {@link AiHubConfiguration#componentRuleCatalogToolCallbacks} registers on the
 * BUILD agent's searchable tool catalog.
 *
 * <p>
 * {@code testCatalogNeverIncludesAReadName} is the load-bearing one.
 * {@link ComponentRuleToolCallbacksFactory#writeToolCallbacks()} calls
 * {@link ComponentRuleToolCallbacksFactory#readToolCallbacks()} internally, which constructs FRESH callback objects, so
 * an identity-based {@code List.contains} filter in the catalog helper would match nothing, demote nothing, and pin
 * {@code createComponentRule} alongside the reads — a silent regression the exact-set assertions here fail on.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubConfigurationComponentRuleFlatCrudToolCallbacksTest {

    private static final Set<String> EXPECTED_READ_NAMES = Set.of(
        "listComponentRules", "describeComponentToolParameters", "proposeComponentRuleCondition");

    private static final Set<String> EXPECTED_MUTATION_NAMES = Set.of("createComponentRule");

    @Test
    void testFlatCrudReturnsExactlyTheThreeReadNames() {
        List<ToolCallback> toolCallbacks = AiHubConfiguration.componentRuleFlatCrudToolCallbacks(present());

        assertThat(toolCallbacks)
            .extracting(toolCallback -> toolCallback.getToolDefinition()
                .name())
            .containsExactlyInAnyOrderElementsOf(EXPECTED_READ_NAMES);
    }

    @Test
    void testFlatCrudReturnsEmptyListWhenFactoryAbsent() {
        assertThat(AiHubConfiguration.componentRuleFlatCrudToolCallbacks(absent())).isEmpty();
    }

    @Test
    void testCatalogReturnsExactlyTheOneMutationName() {
        List<ToolCallback> toolCallbacks = AiHubConfiguration.componentRuleCatalogToolCallbacks(present());

        assertThat(toolCallbacks)
            .extracting(toolCallback -> toolCallback.getToolDefinition()
                .name())
            .containsExactlyInAnyOrderElementsOf(EXPECTED_MUTATION_NAMES);
    }

    @Test
    void testCatalogNeverIncludesAReadName() {
        List<ToolCallback> toolCallbacks = AiHubConfiguration.componentRuleCatalogToolCallbacks(present());

        assertThat(toolCallbacks)
            .extracting(toolCallback -> toolCallback.getToolDefinition()
                .name())
            .doesNotContainAnyElementsOf(EXPECTED_READ_NAMES);
    }

    @Test
    void testCatalogReturnsEmptyListWhenFactoryAbsent() {
        assertThat(AiHubConfiguration.componentRuleCatalogToolCallbacks(absent())).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ComponentRuleToolCallbacksFactory> present() {
        ComponentRuleToolCallbacksFactory factory = new ComponentRuleToolCallbacksFactory(
            mock(ComponentRuleService.class), mock(ComponentDefinitionService.class),
            mock(ClusterElementDefinitionService.class), mock(Evaluator.class));

        ObjectProvider<ComponentRuleToolCallbacksFactory> provider = mock(ObjectProvider.class);

        when(provider.getIfAvailable()).thenReturn(factory);

        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ComponentRuleToolCallbacksFactory> absent() {
        return mock(ObjectProvider.class);
    }
}

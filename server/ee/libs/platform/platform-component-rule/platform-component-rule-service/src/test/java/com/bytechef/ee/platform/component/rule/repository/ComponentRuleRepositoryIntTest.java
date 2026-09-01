/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.platform.component.rule.ComponentRule;
import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.config.ComponentRuleIntTestConfiguration;
import com.bytechef.ee.platform.component.rule.service.ComponentRuleServiceImpl;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Exercises {@link ComponentRuleRepository} and {@link ComponentRuleServiceImpl} against a real PostgreSQL database,
 * where the earlier unit tests could not: both {@code ComponentRuleServiceTest} and
 * {@code ComponentRuleGraphQlControllerTest} mock the repository, so neither one exercises Spring Data JDBC's
 * new-vs-existing decision — the exact place {@code ComponentRuleServiceImpl.saveComponentRule} used to break, because
 * {@link ComponentRule} carries a primitive {@code @Version} field that Spring Data prefers over the id when deciding
 * whether a {@code save()} is an INSERT or an UPDATE.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@SpringBootTest(classes = ComponentRuleIntTestConfiguration.class, properties = "bytechef.edition=ee")
@Import(PostgreSQLContainerConfiguration.class)
public class ComponentRuleRepositoryIntTest {

    @Autowired
    private ComponentRuleRepository componentRuleRepository;

    @Autowired
    private ComponentRuleServiceImpl componentRuleService;

    @AfterEach
    public void afterEach() {
        componentRuleRepository.deleteAll();
    }

    @Test
    void testInsertRoundTripsEveryColumn() {
        ComponentRule componentRule = new ComponentRule();

        componentRule.setComponentName("slack");
        componentRule.setActionName("sendMessage");
        componentRule.setPhase(RulePhase.AFTER);
        componentRule.setRuleAction(RuleAction.TAG);
        componentRule.setCondition("output['ok'] == false");
        componentRule.setDescription("Flag failed sends");
        componentRule.setEnabled(true);

        ComponentRule savedComponentRule = componentRuleRepository.save(componentRule);

        assertThat(savedComponentRule.getId()).isNotNull();

        ComponentRule reloadedComponentRule = componentRuleRepository.findById(savedComponentRule.getId())
            .orElseThrow();

        assertThat(reloadedComponentRule.getComponentName()).isEqualTo("slack");
        assertThat(reloadedComponentRule.getActionName()).isEqualTo("sendMessage");
        // Persisted as an INT ordinal — never exercised against a real database before this test.
        assertThat(reloadedComponentRule.getPhase()).isEqualTo(RulePhase.AFTER);
        assertThat(reloadedComponentRule.getRuleAction()).isEqualTo(RuleAction.TAG);
        // "condition" is a reserved word in some SQL dialects; round-tripping it proves the column is quoted/escaped
        // correctly end to end.
        assertThat(reloadedComponentRule.getCondition()).isEqualTo("output['ok'] == false");
        assertThat(reloadedComponentRule.getDescription()).isEqualTo("Flag failed sends");
        assertThat(reloadedComponentRule.isEnabled()).isTrue();
        // created_by/created_date are NOT NULL columns with no getter on ComponentRule; a save that failed to
        // populate them (as a raw INSERT with a caller-supplied id and no auditing pass would) would have thrown at
        // the JDBC layer, so reaching this assertion at all is proof they round-tripped.
        assertThat(reloadedComponentRule.getId()).isEqualTo(savedComponentRule.getId());
    }

    @Test
    void testSaveComponentRuleUpdatesInPlaceRatherThanInserting() {
        ComponentRule componentRule = new ComponentRule();

        componentRule.setComponentName("slack");
        componentRule.setActionName("sendMessage");
        componentRule.setPhase(RulePhase.BEFORE);
        componentRule.setRuleAction(RuleAction.BLOCK);
        componentRule.setCondition("true");
        componentRule.setEnabled(true);

        ComponentRule savedComponentRule = componentRuleService.saveComponentRule(componentRule);
        Long id = savedComponentRule.getId();

        ComponentRule updateComponentRule = new ComponentRule();

        updateComponentRule.setId(id);
        updateComponentRule.setComponentName("slack");
        updateComponentRule.setActionName("sendMessage");
        updateComponentRule.setPhase(RulePhase.BEFORE);
        updateComponentRule.setRuleAction(RuleAction.TAG);
        updateComponentRule.setCondition("contains(inputParameters['channel'], 'C05')");
        updateComponentRule.setDescription("Now a tag rule");
        updateComponentRule.setEnabled(false);

        componentRuleService.saveComponentRule(updateComponentRule);

        List<ComponentRule> allComponentRules = componentRuleRepository.findAllByComponentName("slack");

        // The bug this test pins: an id-carrying save used to INSERT a second row instead of updating the first.
        assertThat(allComponentRules).hasSize(1);

        ComponentRule reloadedComponentRule = componentRuleRepository.findById(id)
            .orElseThrow();

        assertThat(reloadedComponentRule.getRuleAction()).isEqualTo(RuleAction.TAG);
        assertThat(reloadedComponentRule.getCondition()).isEqualTo("contains(inputParameters['channel'], 'C05')");
        assertThat(reloadedComponentRule.getDescription()).isEqualTo("Now a tag rule");
        assertThat(reloadedComponentRule.isEnabled()).isFalse();
    }

    @Test
    void testFindAllByComponentNameAndEnabledReturnsOnlyEnabledRows() {
        ComponentRule enabledComponentRule = new ComponentRule();

        enabledComponentRule.setComponentName("slack");
        enabledComponentRule.setActionName("sendMessage");
        enabledComponentRule.setPhase(RulePhase.BEFORE);
        enabledComponentRule.setRuleAction(RuleAction.TAG);
        enabledComponentRule.setCondition("true");
        enabledComponentRule.setEnabled(true);

        ComponentRule disabledComponentRule = new ComponentRule();

        disabledComponentRule.setComponentName("slack");
        disabledComponentRule.setActionName("deleteMessage");
        disabledComponentRule.setPhase(RulePhase.BEFORE);
        disabledComponentRule.setRuleAction(RuleAction.BLOCK);
        disabledComponentRule.setCondition("true");
        disabledComponentRule.setEnabled(false);

        componentRuleRepository.save(enabledComponentRule);
        componentRuleRepository.save(disabledComponentRule);

        List<ComponentRule> enabledComponentRules =
            componentRuleRepository.findAllByComponentNameAndEnabled("slack", true);

        assertThat(enabledComponentRules).hasSize(1);
        assertThat(enabledComponentRules.getFirst()
            .getActionName()).isEqualTo("sendMessage");
    }
}

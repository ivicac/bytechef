/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.ee.platform.ai.guardrails.config.AiGuardrailViolationIntTestConfiguration;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailCustomRule;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the custom-rule changelog and entity agree, against a real Postgres.
 *
 * <p>
 * This changeset added no {@code includeAll} line to {@code master.xml} — the directory was already registered by
 * {@code ai_guardrail_violation}, and {@code includeAll} picks up whatever is in it. That convenience is exactly why
 * this test exists: registration carries {@code errorIfMissingOrEmpty="false"}, so a mis-filed changelog is silently
 * skipped and the first symptom would be a failing insert in production.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ActiveProfiles("testint")
@SpringBootTest(classes = AiGuardrailViolationIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
public class AiGuardrailCustomRuleRepositoryIntTest {

    @Autowired
    private AiGuardrailCustomRuleRepository aiGuardrailCustomRuleRepository;

    @Test
    public void testTheChangelogCreatesATableTheEntityCanRoundTrip() {
        AiGuardrailCustomRule saved = aiGuardrailCustomRuleRepository.save(
            new AiGuardrailCustomRule(
                42L, "ACME_ACCOUNT_ID", "\\bACME-\\d{4}-[A-Z]{2}\\b", 0, new BigDecimal("0.90"),
                "acme account,acme id", 40, new BigDecimal("0.95")));

        assertThat(saved.getId()).isNotNull();

        AiGuardrailCustomRule reloaded = aiGuardrailCustomRuleRepository.findById(saved.getId())
            .orElseThrow();

        assertThat(reloaded.getType()).isEqualTo("ACME_ACCOUNT_ID");
        assertThat(reloaded.getPattern()).isEqualTo("\\bACME-\\d{4}-[A-Z]{2}\\b");
        assertThat(reloaded.getWorkspaceId()).isEqualTo(42L);
        assertThat(reloaded.getContextKeywords()).isEqualTo("acme account,acme id");
        assertThat(reloaded.getContextWindow()).isEqualTo(40);

        // @CreatedDate has to populate, and enabled has to default false in the DATABASE and not only in the service
        // -- a row inserted by anything but the service must still be inert.
        assertThat(reloaded.getCreatedDate()).isNotNull();
        assertThat(reloaded.isEnabled()).isFalse();
    }

    @Test
    public void testAWorkspaceCannotDefineTheSameTypeTwice() {
        // The type is the span category AND the token name, so two rules sharing it would mint tokens that cannot be
        // told apart. Enforced in the database rather than only in a service check, because a concurrent pair of
        // creates both pass a read-then-write check.
        aiGuardrailCustomRuleRepository.save(
            new AiGuardrailCustomRule(
                7L, "ACME_DUPLICATE", "\\bA\\d{4}\\b", 0, new BigDecimal("0.90"), null, null, null));

        assertThatThrownBy(
            () -> aiGuardrailCustomRuleRepository.save(
                new AiGuardrailCustomRule(
                    7L, "ACME_DUPLICATE", "\\bB\\d{4}\\b", 0, new BigDecimal("0.90"), null, null, null)))
                        .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    public void testTwoWorkspacesMayEachDefineTheSameType() {
        // The constraint is (workspace_id, type), not type. A tenant-wide uniqueness would make one workspace's
        // naming choice block another's.
        aiGuardrailCustomRuleRepository.save(
            new AiGuardrailCustomRule(
                11L, "ACME_SHARED_NAME", "\\bA\\d{4}\\b", 0, new BigDecimal("0.90"), null, null, null));

        AiGuardrailCustomRule other = aiGuardrailCustomRuleRepository.save(
            new AiGuardrailCustomRule(
                12L, "ACME_SHARED_NAME", "\\bA\\d{4}\\b", 0, new BigDecimal("0.90"), null, null, null));

        assertThat(other.getId()).isNotNull();
    }

    @Test
    public void testTheEnabledQueryReturnsOnlyEnabledRules() {
        AiGuardrailCustomRule disabled = aiGuardrailCustomRuleRepository.save(
            new AiGuardrailCustomRule(
                21L, "ACME_OFF", "\\bA\\d{4}\\b", 0, new BigDecimal("0.90"), null, null, null));
        AiGuardrailCustomRule enabled = aiGuardrailCustomRuleRepository.save(
            new AiGuardrailCustomRule(
                21L, "ACME_ON", "\\bB\\d{4}\\b", 0, new BigDecimal("0.90"), null, null, null));

        enabled.setEnabled(true);

        aiGuardrailCustomRuleRepository.save(enabled);

        assertThat(aiGuardrailCustomRuleRepository.findAllByWorkspaceIdAndEnabledTrueOrderByTypeAsc(21L))
            .extracting(AiGuardrailCustomRule::getType)
            .containsExactly("ACME_ON")
            .doesNotContain(disabled.getType());
    }

    @Test
    public void testARuleIdFromAnotherWorkspaceReadsAsAbsent() {
        // Not forbidden — absent. A distinguishable denial would confirm that another workspace's rule exists, which
        // is the probe oracle this codebase closes the same way for variables and for violation records.
        AiGuardrailCustomRule saved = aiGuardrailCustomRuleRepository.save(
            new AiGuardrailCustomRule(
                31L, "ACME_SCOPED", "\\bA\\d{4}\\b", 0, new BigDecimal("0.90"), null, null, null));

        assertThat(aiGuardrailCustomRuleRepository.findByIdAndWorkspaceId(saved.getId(), 32L)).isEmpty();
        assertThat(aiGuardrailCustomRuleRepository.findByIdAndWorkspaceId(saved.getId(), 31L)).isPresent();
    }
}

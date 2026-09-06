/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.platform.ai.guardrails.config.AiGuardrailViolationIntTestConfiguration;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailViolation;
import com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailViolationAction;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves against a real Postgres that the changelog and the entity agree.
 *
 * <p>
 * This is not ceremony. {@code master.xml}'s {@code includeAll} carries {@code errorIfMissingOrEmpty="false"}, so a
 * wrong path is <b>silently skipped</b> — the application starts, the table simply never exists, and the first symptom
 * is a failing insert in production. A unit test with a mocked repository proves nothing about either the path or the
 * column mapping.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ActiveProfiles("testint")
@SpringBootTest(classes = AiGuardrailViolationIntTestConfiguration.class)
@Import(PostgreSQLContainerConfiguration.class)
public class AiGuardrailViolationRepositoryIntTest {

    @Autowired
    private AiGuardrailViolationRepository aiGuardrailViolationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void testTheChangelogCreatesATableTheEntityCanRoundTrip() {
        AiGuardrailViolation saved = aiGuardrailViolationRepository.save(
            new AiGuardrailViolation(
                "EMAIL_ADDRESS", 0, 5, 11, new BigDecimal("0.90"), AiGuardrailViolationAction.REDACTED, "ai_hub",
                42L, 1, "admin@localhost.com"));

        assertThat(saved.getId()).isNotNull();

        AiGuardrailViolation reloaded = aiGuardrailViolationRepository.findById(saved.getId())
            .orElseThrow();

        assertThat(reloaded.getCategory()).isEqualTo("EMAIL_ADDRESS");
        assertThat(reloaded.getSpanStart()).isEqualTo(5);
        assertThat(reloaded.getSpanLength()).isEqualTo(11);
        assertThat(reloaded.getAction()).isEqualTo(AiGuardrailViolationAction.REDACTED);
        assertThat(reloaded.getSurface()).isEqualTo("ai_hub");
        assertThat(reloaded.getWorkspaceId()).isEqualTo(42L);

        // @CreatedDate has to actually populate, or the retention sweep deletes on a null column forever.
        assertThat(reloaded.getCreatedDate()).isNotNull();
    }

    /**
     * The table must have no column capable of holding a matched value. Asserted against the live schema rather than
     * against the entity, because the entity is not what a future migration would change — someone adding the "debug
     * mode that keeps the raw value for 24 hours" would add a column, and this is the only test that would notice.
     */
    @Test
    public void testTheSchemaHasNoColumnForAMatchedValue() {
        List<String> columns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'ai_guardrail_violation'",
            String.class);

        assertThat(columns)
            .containsExactlyInAnyOrder(
                "id", "workspace_id", "environment", "surface", "category", "kind", "span_start", "span_length",
                "confidence", "action", "principal", "created_date");
    }

    @Test
    public void testTheNullWorkspaceBucketIsCountedRatherThanSkipped() {
        // countForWorkspaceSince uses IS NOT DISTINCT FROM precisely for this. A plain = never matches null, and the
        // null bucket is where every unattributed call lands -- so a = would leave exactly that bucket uncapped.
        aiGuardrailViolationRepository.save(
            new AiGuardrailViolation(
                "US_SSN", 0, 0, 11, new BigDecimal("0.60"), AiGuardrailViolationAction.BLOCKED, "ai_agent",
                null, null, null));

        long counted = aiGuardrailViolationRepository.countForWorkspaceSince(
            null, Instant.now()
                .minus(Duration.ofDays(1)));

        assertThat(counted).isPositive();
    }

    @Test
    public void testRetentionDeletesOnlyRowsOlderThanTheCutoff() {
        aiGuardrailViolationRepository.save(
            new AiGuardrailViolation(
                "EMAIL_ADDRESS", 0, 0, 11, new BigDecimal("0.90"), AiGuardrailViolationAction.REDACTED, "copilot",
                7L, 1, null));

        int deleted = aiGuardrailViolationRepository.deleteByCreatedDateBefore(
            Instant.now()
                .minus(Duration.ofDays(30)));

        assertThat(deleted).isZero();
        assertThat(aiGuardrailViolationRepository.findAllByWorkspaceIdOrderByCreatedDateDesc(7L)).isNotEmpty();
    }
}

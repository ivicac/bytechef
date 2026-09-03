/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayEmbeddedSettings;
import com.bytechef.ee.platform.ai.gateway.exception.AiGatewayEmbeddedSettingsConflictException;
import com.bytechef.platform.configuration.domain.Property;
import com.bytechef.platform.configuration.service.PropertyService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Round-trips {@link AiGatewayEmbeddedSettingsService} through the real {@code property} table (Testcontainers
 * PostgreSQL) instead of a mocked {@link PropertyService} -- the only place the one-row-per-environment invariant and
 * the {@link org.springframework.dao.DataIntegrityViolationException} -&gt;
 * {@link AiGatewayEmbeddedSettingsConflictException} translation in {@code AiGatewayEmbeddedSettingsServiceImpl#upsert}
 * are actually exercised against the partial unique index {@code uk_property_key_scope_environment_null_scope_id}
 * (changelog {@code 20260825000001}). Modeled on {@code VariableServiceIntTest} in {@code platform-variable-service},
 * which hit this same index first, for the same {@code Scope.EMBEDDED}/null-{@code scopeId} row shape.
 *
 * <p>
 * Test methods share one Spring context and one database and never reset state between them, so each method uses an
 * environment id no other method writes into -- otherwise assertions become order-dependent on JUnit's unspecified
 * method ordering.
 *
 * @version ee
 */
@SpringBootTest(classes = AiGatewayEmbeddedSettingsIntTestConfiguration.class)
@ActiveProfiles("testint")
// The "testint" profile's application-testint.yml is shared with AiGatewayChatModelFactoryCacheTest (a plain,
// DB-less @SpringBootTest in this module needing bytechef.ai.gateway.enabled=true), so the liquibase context
// restriction this test needs lives here instead of in that shared file.
@TestPropertySource(properties = "spring.liquibase.contexts=configuration")
class AiGatewayEmbeddedSettingsServiceIntTest {

    @Autowired
    private AiGatewayEmbeddedSettingsService aiGatewayEmbeddedSettingsService;

    @Autowired
    private PropertyService propertyService;

    @Test
    void testUpsertTwiceForSameEnvironmentUpdatesSingleRow() {
        aiGatewayEmbeddedSettingsService.upsert(
            new AiGatewayEmbeddedSettings(101L, 1, 1000, true, 60, 7, 5L, 50, new BigDecimal("25.00")));
        aiGatewayEmbeddedSettingsService.upsert(
            new AiGatewayEmbeddedSettings(101L, 3, 2000, false, 120, 14, 9L, 80, new BigDecimal("40.00")));

        AiGatewayEmbeddedSettings settings = aiGatewayEmbeddedSettingsService.find(101L)
            .orElseThrow();

        // The persisted value must reflect the SECOND call -- proving upsert() updated the existing row rather
        // than leaving the first call's row untouched alongside a new one.
        assertThat(settings.retryCount()).isEqualTo(3);
        assertThat(settings.timeoutMs()).isEqualTo(2000);
        assertThat(settings.cacheEnabled()).isFalse();
        assertThat(settings.defaultRoutingPolicyId()).isEqualTo(9L);
        assertThat(settings.softBudgetWarningPct()).isEqualTo(80);
        assertThat(settings.defaultConnectedUserBudgetCap()).isEqualByComparingTo(new BigDecimal("40.00"));

        assertThat(propertyService.getPropertiesByKeyPrefix(
            AiGatewayEmbeddedSettings.PROPERTY_KEY, Property.Scope.EMBEDDED, null, 101L)).hasSize(1);
    }

    @Test
    void testUpsertForDifferentEnvironmentsProducesTwoRows() {
        aiGatewayEmbeddedSettingsService.upsert(
            new AiGatewayEmbeddedSettings(102L, 1, 1000, true, 60, 7, 5L, 50, new BigDecimal("25.00")));
        aiGatewayEmbeddedSettingsService.upsert(
            new AiGatewayEmbeddedSettings(103L, 2, 1500, false, 90, 10, 6L, 60, new BigDecimal("30.00")));

        assertThat(aiGatewayEmbeddedSettingsService.find(102L)
            .orElseThrow()
            .retryCount()).isEqualTo(1);
        assertThat(aiGatewayEmbeddedSettingsService.find(103L)
            .orElseThrow()
            .retryCount()).isEqualTo(2);
        assertThat(aiGatewayEmbeddedSettingsService.find(102L)
            .orElseThrow()
            .defaultConnectedUserBudgetCap()).isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(aiGatewayEmbeddedSettingsService.find(103L)
            .orElseThrow()
            .defaultConnectedUserBudgetCap()).isEqualByComparingTo(new BigDecimal("30.00"));

        // The partial index is keyed on (key, scope, environment) WHERE scope_id IS NULL -- an index scoped too
        // broadly (e.g. dropping environment from the key) would collapse these into one row. Per-environment row
        // counts prove both writes landed as distinct rows rather than one clobbering the other. A global,
        // cross-environment query for this key is deliberately avoided here: test methods share one database (see
        // the class javadoc), so it would also see rows written by sibling test methods.
        assertThat(propertyService.getPropertiesByKeyPrefix(
            AiGatewayEmbeddedSettings.PROPERTY_KEY, Property.Scope.EMBEDDED, null, 102L)).hasSize(1);
        assertThat(propertyService.getPropertiesByKeyPrefix(
            AiGatewayEmbeddedSettings.PROPERTY_KEY, Property.Scope.EMBEDDED, null, 103L)).hasSize(1);
    }

    @Test
    void testConcurrentUpsertForSameEnvironmentRejectsOneSide() throws Exception {
        // Reproduces the race the partial index (20260825000001) exists to close: two threads both call upsert()
        // for the same not-yet-existing environment, so both can pass PropertyServiceImpl#save's internal
        // find-then-insert check before either has committed. Before that index existed, both inserts would
        // succeed (Scope.EMBEDDED's scope_id is always null, and uk_property_key_scope_scope_id_environment never
        // fires when scope_id is null on both sides), leaving two rows for the same (key, environment). With the
        // partial index in place, exactly one insert wins and the other is translated to
        // AiGatewayEmbeddedSettingsConflictException rather than surfacing as a raw DataIntegrityViolationException.
        long environmentId = 104L;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        try {
            Callable<Optional<AiGatewayEmbeddedSettingsConflictException>> raceTask = () -> {
                start.await();

                try {
                    aiGatewayEmbeddedSettingsService.upsert(
                        new AiGatewayEmbeddedSettings(environmentId, 1, 1000, true, 60, 7, null, null, null));

                    return Optional.empty();
                } catch (AiGatewayEmbeddedSettingsConflictException aiGatewayEmbeddedSettingsConflictException) {
                    return Optional.of(aiGatewayEmbeddedSettingsConflictException);
                }
            };

            Future<Optional<AiGatewayEmbeddedSettingsConflictException>> firstFuture =
                executorService.submit(raceTask);
            Future<Optional<AiGatewayEmbeddedSettingsConflictException>> secondFuture =
                executorService.submit(raceTask);

            start.countDown();

            Optional<AiGatewayEmbeddedSettingsConflictException> firstOutcome = firstFuture.get(10, TimeUnit.SECONDS);
            Optional<AiGatewayEmbeddedSettingsConflictException> secondOutcome =
                secondFuture.get(10, TimeUnit.SECONDS);

            List<AiGatewayEmbeddedSettingsConflictException> rejections = Stream.of(firstOutcome, secondOutcome)
                .flatMap(Optional::stream)
                .toList();

            assertThat(rejections)
                .as("exactly one racer must be rejected as a conflict")
                .hasSize(1);

            assertThat(propertyService.getPropertiesByKeyPrefix(
                AiGatewayEmbeddedSettings.PROPERTY_KEY, Property.Scope.EMBEDDED, null, environmentId)).hasSize(1);
        } finally {
            executorService.shutdownNow();
        }
    }
}

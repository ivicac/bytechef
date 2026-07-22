/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiGatewayGuardrailMetricsTest {

    @Test
    void testRecordIncrementsCounterByEvent() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AiGatewayGuardrailMetrics metrics = new AiGatewayGuardrailMetrics(meterRegistry);

        metrics.record("pii_redacted");
        metrics.record("pii_redacted");
        metrics.record("blocked_term");

        assertThat(meterRegistry.counter(AiGatewayGuardrailMetrics.COUNTER_NAME, "event", "pii_redacted")
            .count()).isEqualTo(2.0);
        assertThat(meterRegistry.counter(AiGatewayGuardrailMetrics.COUNTER_NAME, "event", "blocked_term")
            .count()).isEqualTo(1.0);
    }

    @Test
    void testRecordIsNoOpWithoutRegistry() {
        AiGatewayGuardrailMetrics metrics = new AiGatewayGuardrailMetrics((MeterRegistry) null);

        assertThatCode(() -> metrics.record("pii_redacted")).doesNotThrowAnyException();
    }
}

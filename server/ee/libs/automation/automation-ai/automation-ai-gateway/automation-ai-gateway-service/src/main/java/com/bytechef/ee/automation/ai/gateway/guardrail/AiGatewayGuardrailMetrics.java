/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.gateway.guardrail;

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Counts AI Gateway guardrail activity so operators can see what the DLP layer is catching. Emits a single
 * {@code bytechef_ai_gateway_guardrail} counter tagged by {@code event} — one of {@code pii_redacted},
 * {@code secret_redacted}, {@code response_redacted}, {@code blocked_term}, {@code moderation_flagged}, or
 * {@code injection_flagged}. Only the low-cardinality {@code event} tag is used (no workspace/project dimension) so the
 * meter stays cheap on unbounded multi-tenant deployments. Wired through {@link ObjectProvider} so lightweight app
 * variants without an actuator {@link MeterRegistry} start cleanly and recording is a no-op.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
public class AiGatewayGuardrailMetrics {

    static final String COUNTER_NAME = "bytechef_ai_gateway_guardrail";

    private final @Nullable MeterRegistry meterRegistry;

    // Two constructors are declared, so Spring cannot pick an autowire candidate implicitly and would fall back to a
    // (non-existent) default constructor. @Autowired marks this one as the container's entry point.
    @Autowired
    public AiGatewayGuardrailMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(meterRegistryProvider.getIfAvailable());
    }

    AiGatewayGuardrailMetrics(@Nullable MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Increments the guardrail counter for the given {@code event}. No-op when no {@link MeterRegistry} is present.
     *
     * @param event one of the documented event names
     */
    public void record(String event) {
        if (meterRegistry == null) {
            return;
        }

        Counter.builder(COUNTER_NAME)
            .description("AI Gateway guardrail actions by event type")
            .tag("event", event)
            .register(meterRegistry)
            .increment();
    }
}

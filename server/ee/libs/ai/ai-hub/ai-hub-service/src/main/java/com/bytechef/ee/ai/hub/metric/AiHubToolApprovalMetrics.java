/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.metric;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Counter for the tool approval gate. Wired via {@link ObjectProvider}{@code <MeterRegistry>} so app variants without
 * actuator start cleanly — {@link #record(String)} is a no-op when no {@code MeterRegistry} is available.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
public class AiHubToolApprovalMetrics {

    public static final String APPROVAL_COUNTER = "bytechef_ai_hub_tool_approval";

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public AiHubToolApprovalMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * Records one gate outcome. Tag {@code outcome}: {@code requested} (a pending row was created and the envelope
     * returned to the LLM), {@code deferred} (another approval was already pending for the chat), {@code refused} (the
     * pending row could not be persisted, so the tool was not executed).
     */
    public void record(String outcome) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();

        if (meterRegistry == null) {
            return;
        }

        Counter.builder(APPROVAL_COUNTER)
            .tag("outcome", outcome == null ? "unknown" : outcome)
            .register(meterRegistry)
            .increment();
    }
}

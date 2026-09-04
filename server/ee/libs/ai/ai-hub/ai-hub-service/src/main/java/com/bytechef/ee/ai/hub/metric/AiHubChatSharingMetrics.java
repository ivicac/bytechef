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
 * Counters for the shared-chat surface: how often a chat's visibility is changed ({@code bytechef_ai_hub_chat_share}),
 * and how often the one-turn-in-flight-per-chat guard rejects a concurrent turn
 * ({@code bytechef_ai_hub_chat_turn_conflict}).
 *
 * <p>
 * Wired via {@link ObjectProvider}{@code <MeterRegistry>} so app variants without actuator start cleanly — every record
 * method is a no-op when no {@code MeterRegistry} is available, mirroring {@link WorkflowChatMetrics} and
 * {@link AiHubToolAttachMetrics}.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
public class AiHubChatSharingMetrics {

    public static final String SHARE_COUNTER = "bytechef_ai_hub_chat_share";
    public static final String TURN_CONFLICT_COUNTER = "bytechef_ai_hub_chat_turn_conflict";

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public AiHubChatSharingMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * Records a chat visibility change. Tag {@code visibility} is the {@code ResourceVisibility} name the chat was set
     * to ({@code PRIVATE}, {@code WORKSPACE}; {@code ORGANIZATION} is rejected by the sharing facade before this is
     * reached).
     */
    public void recordShare(String visibility) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();

        if (meterRegistry == null) {
            return;
        }

        Counter.builder(SHARE_COUNTER)
            .tag("visibility", visibility == null ? "unknown" : visibility)
            .register(meterRegistry)
            .increment();
    }

    /**
     * Records a rejected turn: the one-turn-in-flight-per-chat guard found another user's turn already running on the
     * thread and the controller returned 409 instead of starting a new one.
     */
    public void recordTurnConflict() {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();

        if (meterRegistry == null) {
            return;
        }

        Counter.builder(TURN_CONFLICT_COUNTER)
            .register(meterRegistry)
            .increment();
    }
}

/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayModelTier;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class RoutingOutcomeMetricsTest {

    private static final List<AiGatewayModelTier> TWO_TIERS = List.of(
        AiGatewayModelTier.BASIC, AiGatewayModelTier.FRONTIER);

    @Test
    void testCostAxisSkewsTowardTheCheapTier() {
        double[] scores = {
            0.1, 0.3, 0.5, 0.6
        };

        Map<AiGatewayModelTier, Integer> distribution = RoutingOutcomeMetrics.tierDistribution(
            scores, AiGatewayRoutingStrategyType.INTELLIGENT_COST, TWO_TIERS);

        assertThat(distribution.get(AiGatewayModelTier.BASIC)).isEqualTo(4);
    }

    @Test
    void testQualityAxisSkewsTowardTheCapableTier() {
        double[] scores = {
            0.4, 0.5, 0.7, 0.9
        };

        Map<AiGatewayModelTier, Integer> distribution = RoutingOutcomeMetrics.tierDistribution(
            scores, AiGatewayRoutingStrategyType.INTELLIGENT_QUALITY, TWO_TIERS);

        assertThat(distribution.get(AiGatewayModelTier.FRONTIER)).isEqualTo(4);
    }

    @Test
    void testBalancedAxisSplitsAtTheMidpoint() {
        double[] scores = {
            0.1, 0.4, 0.6, 0.9
        };

        Map<AiGatewayModelTier, Integer> distribution = RoutingOutcomeMetrics.tierDistribution(
            scores, AiGatewayRoutingStrategyType.INTELLIGENT_BALANCED, TWO_TIERS);

        assertThat(distribution.get(AiGatewayModelTier.BASIC)).isEqualTo(2);
        assertThat(distribution.get(AiGatewayModelTier.FRONTIER)).isEqualTo(2);
    }

    @Test
    void testEveryPresentTierAppearsInTheDistribution() {
        double[] scores = {
            0.5
        };

        Map<AiGatewayModelTier, Integer> distribution = RoutingOutcomeMetrics.tierDistribution(
            scores, AiGatewayRoutingStrategyType.INTELLIGENT_BALANCED, TWO_TIERS);

        assertThat(distribution).containsKeys(AiGatewayModelTier.BASIC, AiGatewayModelTier.FRONTIER);
    }
}

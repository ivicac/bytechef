/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayModelTier;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reproduces the tier selection arithmetic of {@code IntelligentRoutingStrategy} so a set of scores can be turned into
 * a routing distribution without constructing deployments and a routing context. The transforms below must stay
 * identical to that class; the unit tests pin them.
 *
 * @version ee
 */
public final class RoutingOutcomeMetrics {

    private RoutingOutcomeMetrics() {
    }

    public static Map<AiGatewayModelTier, Integer> tierDistribution(
        double[] scores, AiGatewayRoutingStrategyType axis, List<AiGatewayModelTier> presentTiers) {

        Map<AiGatewayModelTier, Integer> distribution = new EnumMap<>(AiGatewayModelTier.class);

        for (AiGatewayModelTier tier : presentTiers) {
            distribution.put(tier, 0);
        }

        for (double score : scores) {
            AiGatewayModelTier tier = presentTiers.get(indexOf(score, axis, presentTiers.size()));

            distribution.merge(tier, 1, Integer::sum);
        }

        return distribution;
    }

    private static int indexOf(double score, AiGatewayRoutingStrategyType axis, int tierCount) {
        double effective = transform(score, axis);

        int index = (int) Math.floor(effective * tierCount);

        return Math.max(0, Math.min(index, tierCount - 1));
    }

    private static double transform(double score, AiGatewayRoutingStrategyType axis) {
        return switch (axis) {
            case INTELLIGENT_COST -> score * score;
            case INTELLIGENT_BALANCED -> score;
            case INTELLIGENT_QUALITY -> 1.0 - (1.0 - score) * (1.0 - score);
            default -> throw new IllegalArgumentException("Not an intelligent routing axis: " + axis);
        };
    }
}
